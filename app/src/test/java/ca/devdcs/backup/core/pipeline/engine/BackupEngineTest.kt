package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.BackupSource
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.core.pipeline.checksum.DefaultChecksumEngine
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.core.storage.FakeDestinationRepository
import ca.devdcs.backup.core.storage.FreeSpaceVerifier
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.TransferMode
import ca.devdcs.backup.domain.security.Secret
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

class BackupEngineTest {

    private val store = MemoryStore()
    private val registry = MemoryRegistry(store)
    private val destinations = FakeDestinationRepository()
    private val checkpoints = FakeCheckpoints()
    private val history = FakeHistory()
    private val hashes = FakeHashes()
    private val bus = JobProgressBus()
    private val archive = FakeArchiveFactory()
    private val cipher = FakeCipher()
    private val keys = FakeKeyPreparer(cipher)
    private val masker = FakeMaskerProvider()
    private val staging = FakeStaging()
    private var launched = 0

    private val jobDir = "Phone_20261019-080509"
    private val noDelay = RetryPolicy(maxAttempts = 3, initialDelayMillis = 0, multiplier = 1.0, maxDelayMillis = 0)

    private fun TestScope.engine(sources: BackupSourceFactory, vault: Map<String, Secret> = emptyMap()): BackupEngine {
        val io = StandardTestDispatcher(testScheduler)
        return BackupEngine(
            EngineDeps(
                sourceFactory = sources,
                archiveFactory = archive,
                checksumEngine = DefaultChecksumEngine(io),
                keyPreparer = keys,
                maskerProvider = masker,
                storageRegistry = registry,
                destinationRepository = destinations,
                freeSpaceVerifier = FreeSpaceVerifier(registry, destinations) { 5L },
                checkpointRepository = checkpoints,
                historyRepository = history,
                hashRegistryRepository = hashes,
                credentialVault = FakeVault(vault),
                progressBus = bus,
                serviceLauncher = JobServiceLauncher { launched++ },
                deviceInfo = FakeDeviceInfo(),
                stagingArea = staging,
                clock = FixedClock(),
                messages = FakeMessages(),
                io = io,
            ),
        )
    }

    private suspend fun destination(type: DestinationType = DestinationType.LOCAL_INTERNAL) =
        Destination(id = "d1", name = "Dest", type = type, createdAt = 1L, updatedAt = 1L).also { destinations.save(it) }

    private fun profile(options: PipelineOptions = PipelineOptions()) =
        BackupProfile(id = "p1", name = "Phone", destinationId = "d1", pipeline = options, createdAt = 1L, updatedAt = 1L)

    private fun appSource() = FakeSource("app:com.x", listOf(SourceItem.Directory("data"), fileItem("data/a.txt", "hello"), fileItem("data/b.txt", "world!")))

    private fun partitionSource() = FakeSource(
        "partition:boot",
        emptyList(),
        flowOverride = flow { emit(SourceItem.Stream("partitions/boot.img", 8L, sizeIsExact = true) { ByteArrayInputStream("BOOTIMG!".toByteArray()) }) },
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertNoPartials() = assertTrue("partials left: ${store.files.keys}", store.files.keys.none { it.endsWith(".part") })

    @Test
    fun `direct backup writes artifacts sidecars and manifest and records the job`() = runTest {
        destination()
        val events = engine(FakeSourceFactory(listOf(appSource(), partitionSource()))).run(profile(), jobId = "job1").toList()
        val outcome = (events.last() as BackupEvent.Finished).outcome

        assertEquals(outcome.failure.toString(), JobStatus.COMPLETED, outcome.status)
        assertTrue(events.first() is BackupEvent.Progress || events.first() is BackupEvent.Planned)
        assertEquals(1, launched)
        assertNoPartials()

        val archivePath = "$jobDir/app_com.x.tar.zst"
        val archiveBytes = store.files[archivePath]!!
        assertEquals("[zstd]data\ndata/a.txt\nhellodata/b.txt\nworld!END\n", archiveBytes.toString(Charsets.UTF_8))
        assertEquals("${sha256(archiveBytes)}  app_com.x.tar.zst\n", store.text("$archivePath.sha256"))
        val imageBytes = store.files["$jobDir/boot.img.zst"]!!
        assertEquals("[zstd]BOOTIMG!", imageBytes.toString(Charsets.UTF_8))
        assertEquals(listOf(ca.devdcs.backup.domain.model.ArchiveFormat.TAR), archive.writerFormats)

        val manifest = BackupManifest.decode(store.text("$jobDir/dftb-manifest.json"))
        assertEquals("job1", manifest.jobId)
        assertEquals(listOf("app:com.x", "partition:boot"), manifest.artifacts.map { it.sourceId })
        val app = manifest.artifacts[0]
        assertEquals(true, app.verified)
        assertEquals(archiveBytes.size.toLong(), app.sizeBytes)
        assertEquals(11L, app.sourceBytes)
        assertEquals(sha256(archiveBytes), app.digests["sha256"])
        assertEquals(sha256("hello".toByteArray()), app.items.first { it.archivePath == "data/a.txt" }.digests!!["sha256"])
        assertEquals("_b", manifest.device.slotSuffix)

        assertEquals(2, outcome.artifacts.size)
        assertEquals(jobDir, outcome.jobDir)
        assertEquals("$jobDir/dftb-manifest.json", outcome.manifestPath)
        assertEquals(JobStatus.COMPLETED, history.rows["job1"]!!.status)
        assertEquals(jobDir, history.rows["job1"]!!.artifactPath)
        assertEquals(JobStatus.RUNNING, history.saves.first().status)
        val checkpoint = checkpoints.rows["job1"]!!
        assertEquals(JobStatus.COMPLETED, checkpoint.status)
        assertEquals(listOf("app:com.x", "partition:boot"), checkpoint.state.completedItems)
        assertEquals(jobDir, checkpoint.state.extras[BackupJob.EXTRA_JOB_DIR])
        assertEquals(setOf("app_com.x.tar.zst/data/a.txt", "app_com.x.tar.zst/data/b.txt", "boot.img.zst/partitions/boot.img"), hashes.records.map { it.relativePath }.toSet())
        assertTrue(bus.activeJobs.value.isEmpty())
    }

    @Test
    fun `transient network failure is retried`() = runTest {
        destination(DestinationType.SFTP)
        store.openWriteFailure = { path, attempt ->
            if (path.endsWith(".tar.zst.part") && attempt == 1) StorageException(StorageException.Reason.NETWORK, "reset") else null
        }
        val events = engine(FakeSourceFactory(listOf(appSource())))
            .run(profile(PipelineOptions(transferMode = TransferMode.DIRECT_STREAM)), "job2", BackupRunOptions(retryPolicy = noDelay))
            .toList()
        val outcome = (events.last() as BackupEvent.Finished).outcome
        assertEquals(JobStatus.COMPLETED, outcome.status)
        assertTrue(events.any { it is BackupEvent.Retrying })
        assertNoPartials()
    }

    @Test
    fun `persistent storage failure fails the job and leaves it resumable`() = runTest {
        destination(DestinationType.SFTP)
        store.openWriteFailure = { path, _ -> if (path.endsWith(".part")) StorageException(StorageException.Reason.NETWORK, "down") else null }
        val outcome = engine(FakeSourceFactory(listOf(appSource())))
            .execute(profile(PipelineOptions(transferMode = TransferMode.DIRECT_STREAM)), "job3", BackupRunOptions(retryPolicy = noDelay))
        assertEquals(JobStatus.FAILED, outcome.status)
        assertEquals(BackupErrorCode.STORAGE, outcome.failure!!.code)
        assertEquals(StorageException.Reason.NETWORK, outcome.failure.storageReason)
        assertEquals(JobStatus.INTERRUPTED, checkpoints.rows["job3"]!!.status)
        assertEquals(JobStatus.FAILED, history.rows["job3"]!!.status)
    }

    @Test
    fun `insufficient space aborts unless overridden`() = runTest {
        destination()
        store.space = SpaceInfo(freeBytes = 10L, totalBytes = 100L)
        val refused = engine(FakeSourceFactory(listOf(appSource()))).execute(profile(), "job4")
        assertEquals(JobStatus.FAILED, refused.status)
        assertEquals(BackupErrorCode.INSUFFICIENT_DESTINATION_SPACE, refused.failure!!.code)
        assertTrue(store.files.isEmpty())

        val forced = engine(FakeSourceFactory(listOf(appSource()))).execute(profile(), "job5", BackupRunOptions(ignoreInsufficientSpace = true))
        assertEquals(JobStatus.COMPLETED_WITH_WARNINGS, forced.status)
        assertTrue(forced.warnings.any { it.code == BackupWarningCode.FREE_SPACE_OVERRIDDEN })
    }

    @Test
    fun `unsupported modes fail clearly before writing`() = runTest {
        destination(DestinationType.SSH)
        val e = engine(FakeSourceFactory(listOf(appSource())))
        assertEquals(
            BackupErrorCode.TRANSFER_MODE_UNSUPPORTED,
            e.execute(profile(PipelineOptions(transferMode = TransferMode.REMOTE_COMPRESS)), "j6").failure!!.code,
        )
        assertEquals(
            BackupErrorCode.ENCRYPTION_UNSUPPORTED,
            e.execute(profile(PipelineOptions(encryptionMode = EncryptionMode.GPG, encryptionKeyRef = "k")), "j7").failure!!.code,
        )
        assertEquals(
            BackupErrorCode.ENCRYPTION_KEY_MISSING,
            e.execute(profile(PipelineOptions(encryptionMode = EncryptionMode.AES_256_GCM)), "j8").failure!!.code,
        )
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun `encryption with masking hides names and encrypts the manifest`() = runTest {
        destination()
        val options = PipelineOptions(encryptionMode = EncryptionMode.AES_256_GCM, encryptionKeyRef = "k1", maskFilenames = true)
        val vault = mapOf("k1" to Secret.EncryptionKey(keyBase64 = "AAAA", algorithm = "AES-256-GCM"))
        val outcome = engine(FakeSourceFactory(listOf(appSource())), vault).execute(profile(options), "job9")
        assertEquals(outcome.failure.toString(), JobStatus.COMPLETED, outcome.status)

        val stored = HexMasker().mask("app_com.x.tar.zst.dftbenc")
        val data = store.files["$jobDir/$stored"]!!
        assertTrue(data.toString(Charsets.ISO_8859_1).startsWith("ENC\n"))
        assertTrue(store.files.containsKey("$jobDir/$stored.sha256"))
        assertTrue(store.text("$jobDir/dftb-manifest.json.dftbenc").startsWith("ENC\n"))
        assertFalse(store.files.containsKey("$jobDir/dftb-manifest.json"))
        assertTrue(store.files.keys.none { it.contains("app_com.x") })
        assertTrue(masker.closed)
        assertEquals(1, keys.prepared)
        assertEquals(1, keys.closed)
        assertEquals(3, cipher.opened) // artifact + partial manifest + final manifest, one key derivation
    }

    @Test
    fun `staged upload resumes with append after a dropped connection`() = runTest {
        destination(DestinationType.SFTP)
        store.breakAfterBytes = 5
        store.breakPath = { it.endsWith(".tar.zst.part") }
        val outcome = engine(FakeSourceFactory(listOf(appSource())))
            .execute(profile(PipelineOptions(transferMode = TransferMode.LOCAL_STAGING)), "job10", BackupRunOptions(retryPolicy = noDelay))
        assertEquals(outcome.failure.toString(), JobStatus.COMPLETED, outcome.status)
        val bytes = store.files["$jobDir/app_com.x.tar.zst"]!!
        assertEquals("[zstd]data\ndata/a.txt\nhellodata/b.txt\nworld!END\n", bytes.toString(Charsets.UTF_8))
        assertTrue(store.writeOpens.contains("$jobDir/app_com.x.tar.zst.part" to true))
        assertEquals("${sha256(bytes)}  app_com.x.tar.zst\n", store.text("$jobDir/app_com.x.tar.zst.sha256"))
        assertNoPartials()
        assertTrue(staging.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancel from the bus removes partial files and records CANCELLED`() = runTest {
        destination()
        val source = FakeSource(
            "app:com.slow",
            emptyList(),
            flowOverride = flow {
                emit(fileItem("a", "x"))
                bus.requestCancel("job11")
                awaitCancellation()
            },
        )
        val outcome = engine(FakeSourceFactory(listOf(source))).execute(profile(), "job11")
        assertEquals(JobStatus.CANCELLED, outcome.status)
        assertNoPartials()
        assertEquals(JobStatus.CANCELLED, checkpoints.rows["job11"]!!.status)
        assertEquals(JobStatus.CANCELLED, history.rows["job11"]!!.status)
        assertTrue(bus.activeJobs.value.isEmpty())
    }

    @Test
    fun `one failing source does not stop the others`() = runTest {
        destination()
        val broken = FakeSource("app:com.broken", emptyList(), flowOverride = flow { throw IOException("unreadable") })
        val outcome = engine(FakeSourceFactory(listOf(broken, appSource()))).execute(profile(), "job12")
        assertEquals(JobStatus.COMPLETED_WITH_WARNINGS, outcome.status)
        assertEquals(listOf("app:com.x"), outcome.artifacts.map { it.sourceId })
        assertTrue(outcome.warnings.any { it.code == BackupWarningCode.ARTIFACT_FAILED && it.subject == "app_com.broken.tar.zst" })
        assertNoPartials()
        assertEquals(listOf("app:com.x"), checkpoints.rows["job12"]!!.state.completedItems)
    }

    @Test
    fun `unavailable sources become warnings or a clear failure`() = runTest {
        destination()
        val issue = SourceIssue("partitions", "Root is required", "STANDARD", blocking = true)
        val partial = engine(FakeSourceFactory(listOf(appSource()), listOf(issue))).execute(profile(), "job13")
        assertEquals(JobStatus.COMPLETED_WITH_WARNINGS, partial.status)
        assertTrue(partial.warnings.any { it.code == BackupWarningCode.SOURCE_UNAVAILABLE && it.subject == "partitions" })

        val none = engine(FakeSourceFactory(emptyList<BackupSource>(), listOf(issue))).execute(profile(), "job14")
        assertEquals(BackupErrorCode.SOURCES_UNAVAILABLE, none.failure!!.code)
        assertEquals(BackupErrorCode.NOTHING_TO_BACK_UP, engine(FakeSourceFactory(emptyList())).execute(profile(), "job15").failure!!.code)
    }

    @Test
    fun `twrp folders are stored verbatim at the destination root`() = runTest {
        destination()
        val folder = "TWRP/BACKUPS/serial/2026-10-19--08-05-09"
        val twrp = FakeSource("nandroid:$folder", listOf(fileItem("$folder/boot.emmc.win", "IMG"), fileItem("$folder/boot.emmc.win.sha2", "abc  boot.emmc.win\n")))
        val factory = FakeSourceFactory(listOf(twrp), verbatimIds = setOf(twrp.sourceId))
        val outcome = engine(factory).execute(profile(PipelineOptions(twrpCompatible = true)), "job16")
        assertEquals(outcome.failure.toString(), JobStatus.COMPLETED, outcome.status)
        assertEquals("IMG", store.text("$folder/boot.emmc.win"))
        assertEquals("abc  boot.emmc.win\n", store.text("$folder/boot.emmc.win.sha2"))
        assertEquals(folder, outcome.artifacts.single().path)
        assertFalse(outcome.warnings.any { it.code == BackupWarningCode.TWRP_NOT_ON_DEVICE_STORAGE })
        assertTrue(outcome.warnings.any { it.code == BackupWarningCode.TWRP_STORED_VERBATIM })
        val sidecar = store.files.keys.single { it.startsWith("$jobDir/") && it.endsWith(".sha2") }
        assertTrue(store.text(sidecar).contains("  ../$folder/boot.emmc.win\n"))
        val manifest = BackupManifest.decode(store.text("$jobDir/dftb-manifest.json"))
        assertEquals("", manifest.artifacts.single().treeRoot)

        val refused = engine(factory).execute(
            profile(PipelineOptions(twrpCompatible = true, encryptionMode = EncryptionMode.AES_256_GCM, encryptionKeyRef = "k")),
            "job17",
        )
        assertEquals(BackupErrorCode.TWRP_ENCRYPTION_CONFLICT, refused.failure!!.code)
    }

    @Test
    fun `twrp on a network destination warns that it must be copied to the device`() = runTest {
        destination(DestinationType.SMB)
        val twrp = FakeSource("nandroid:TWRP/BACKUPS/s/n", listOf(fileItem("TWRP/BACKUPS/s/n/boot.emmc.win", "IMG")))
        val outcome = engine(FakeSourceFactory(listOf(twrp), verbatimIds = setOf(twrp.sourceId)))
            .execute(profile(PipelineOptions(twrpCompatible = true, transferMode = TransferMode.DIRECT_STREAM)), "job19")
        assertEquals(JobStatus.COMPLETED_WITH_WARNINGS, outcome.status)
        assertTrue(outcome.warnings.any { it.code == BackupWarningCode.TWRP_NOT_ON_DEVICE_STORAGE })
    }

    @Test
    fun `runtime source issues read after the flow degrade the status`() = runTest {
        destination()
        var flowDone = false
        val source = FakeSource("nandroid:TWRP/BACKUPS/s/n", emptyList(), flowOverride = flow {
            emit(fileItem("TWRP/BACKUPS/s/n/data.ext4.win", "TAR"))
            flowDone = true
        })
        val reader = { if (flowDone) listOf(SourceIssue("data", "Files vanished", "3", blocking = false)) else emptyList() }
        val factory = FakeSourceFactory(listOf(source), verbatimIds = setOf(source.sourceId), runtimeIssues = mapOf(source.sourceId to reader))
        val outcome = engine(factory).execute(profile(PipelineOptions(twrpCompatible = true, compressionCodec = ca.devdcs.backup.domain.model.CompressionCodec.NONE, archiveFormat = ca.devdcs.backup.domain.model.ArchiveFormat.TAR)), "job22")
        assertEquals(JobStatus.COMPLETED_WITH_WARNINGS, outcome.status)
        assertTrue(outcome.warnings.any { it.code == BackupWarningCode.SOURCE_PARTIAL && it.subject == "data" })
    }

    @Test
    fun `resumed run writes a sibling manifest instead of overwriting`() = runTest {
        destination()
        val e = engine(FakeSourceFactory(listOf(appSource(), partitionSource())))
        val first = e.execute(profile(), "job23", BackupRunOptions(skipSourceIds = setOf("partition:boot")))
        assertEquals("$jobDir/dftb-manifest.json", first.manifestPath)
        val original = store.text("$jobDir/dftb-manifest.json")

        val resumed = e.execute(profile(), "job23", BackupRunOptions(jobDirName = jobDir, skipSourceIds = setOf("app:com.x")))
        val sibling = "$jobDir/dftb-manifest.1792397109000.json"
        assertEquals(sibling, resumed.manifestPath)
        assertEquals(original, store.text("$jobDir/dftb-manifest.json"))
        // The already-completed app artifact is carried forward from the first manifest.
        assertEquals(listOf("app:com.x", "partition:boot"), BackupManifest.decode(store.text(sibling)).artifacts.map { it.sourceId })
        assertTrue(Regex("dftb-manifest[._-].*\\.json(\\.dftbenc)?").matches(sibling.substringAfterLast('/')))
    }

    @Test
    fun `interrupted run leaves a partial manifest and a masked resume reuses the masking params`() = runTest {
        destination()
        val options = PipelineOptions(encryptionMode = EncryptionMode.AES_256_GCM, encryptionKeyRef = "k1", maskFilenames = true)
        val vault = mapOf("k1" to Secret.EncryptionKey(keyBase64 = "AAAA", algorithm = "AES-256-GCM"))
        val hang = FakeSource("partition:boot", emptyList(), flowOverride = flow {
            emit(SourceItem.Stream("partitions/boot.img", 1L, sizeIsExact = true) { ByteArrayInputStream("B".toByteArray()) })
            bus.requestCancel("job24")
            awaitCancellation()
        })
        val first = engine(FakeSourceFactory(listOf(appSource(), hang)), vault).execute(profile(options), "job24")
        assertEquals(JobStatus.CANCELLED, first.status)
        val manifestPath = "$jobDir/dftb-manifest.json.dftbenc"
        val partial = BackupManifest.decode(keys.decrypt(store.files[manifestPath]!!.inputStream(), ca.devdcs.backup.core.pipeline.CipherKeySpec.VaultKey("k1")).readBytes().toString(Charsets.UTF_8))
        assertTrue(partial.partial)
        assertEquals(listOf("app:com.x"), partial.artifacts.map { it.sourceId })
        val params = checkpoints.rows["job24"]!!.state.extras.filterKeys { it.startsWith(BackupJob.EXTRA_MASK_PREFIX) }
        assertEquals(mapOf("mask.scheme" to "hex", "mask.salt" to "s1"), params)

        // Resume: the checkpoint supplies the folder and the masking params.
        val resumed = engine(FakeSourceFactory(listOf(appSource(), partitionSource())), vault)
            .execute(profile(options), "job24", BackupRunOptions(skipSourceIds = setOf("app:com.x")))
        assertEquals(resumed.failure.toString(), JobStatus.COMPLETED, resumed.status)
        assertEquals(mapOf("scheme" to "hex", "salt" to "s1"), masker.received.last())
        val sibling = resumed.manifestPath!!
        assertTrue(sibling.startsWith("$jobDir/dftb-manifest.") && sibling.endsWith(".json.dftbenc"))
        val merged = BackupManifest.decode(keys.decrypt(store.files[sibling]!!.inputStream(), ca.devdcs.backup.core.pipeline.CipherKeySpec.VaultKey("k1")).readBytes().toString(Charsets.UTF_8))
        assertFalse(merged.partial)
        assertEquals(listOf("app:com.x", "partition:boot"), merged.artifacts.map { it.sourceId })
        assertEquals(mapOf("scheme" to "hex", "salt" to "s1"), merged.masking)
    }

    @Test
    fun `resume reuses a long folder name verbatim and rejects unsafe ones`() = runTest {
        destination()
        val longName = "x".repeat(150) + "_20261019-080509"
        val ok = engine(FakeSourceFactory(listOf(appSource()))).execute(profile(), "job25", BackupRunOptions(jobDirName = longName))
        assertEquals(longName, ok.jobDir)
        val bad = engine(FakeSourceFactory(listOf(appSource()))).execute(profile(), "job26", BackupRunOptions(jobDirName = "../escape"))
        assertEquals(BackupErrorCode.RESUME_FOLDER_INVALID, bad.failure!!.code)
        val fresh = ArtifactNaming.jobDirName("y".repeat(300), 0L, java.time.ZoneOffset.UTC)
        assertEquals(ArtifactNaming.MAX_SEGMENT_LENGTH, fresh.length)
        assertTrue(fresh.endsWith("_19700101-000000"))
    }

    @Test
    fun `masking without encryption is refused`() = runTest {
        destination()
        val outcome = engine(FakeSourceFactory(listOf(appSource()))).execute(profile(PipelineOptions(maskFilenames = true)), "job20")
        assertEquals(BackupErrorCode.MASKING_REQUIRES_ENCRYPTION, outcome.failure!!.code)
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun `caller cancellation leaves the job resumable`() = runTest {
        destination()
        val source = FakeSource("app:com.slow", emptyList(), flowOverride = flow {
            emit(fileItem("a", "x"))
            awaitCancellation()
        })
        val e = engine(FakeSourceFactory(listOf(source)))
        val job = launch { e.execute(profile(), "job21") }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(JobStatus.INTERRUPTED, checkpoints.rows["job21"]!!.status)
        assertNoPartials()
    }

    @Test
    fun `raw copy stores one file per item with the codec suffix`() = runTest {
        destination()
        val options = PipelineOptions(archiveFormat = ca.devdcs.backup.domain.model.ArchiveFormat.RAW_COPY, compressionCodec = ca.devdcs.backup.domain.model.CompressionCodec.GZIP)
        val outcome = engine(FakeSourceFactory(listOf(appSource()))).execute(profile(options), "job18")
        assertEquals(outcome.failure.toString(), JobStatus.COMPLETED, outcome.status)
        assertEquals("[gzip]hello", store.text("$jobDir/app_com.x/data/a.txt.gz"))
        assertEquals("[gzip]world!", store.text("$jobDir/app_com.x/data/b.txt.gz"))
        val sidecar = store.text("$jobDir/app_com.x.sha256")
        assertTrue(sidecar.contains("  app_com.x/data/a.txt.gz\n"))
        assertNotNull(BackupManifest.decode(store.text("$jobDir/dftb-manifest.json")).artifacts.single().items.single { it.archivePath == "data/a.txt" }.storedPath)
    }
}
