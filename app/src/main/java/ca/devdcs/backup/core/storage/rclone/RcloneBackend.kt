package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationConfig
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultException
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StorageBackend] for every destination reached through the rclone binary: generic rclone
 * remotes ([DestinationType.RCLONE]) plus [DestinationType.S3] and [DestinationType.MEGA].
 *
 * Exists so DFTB gets rclone's 50+ providers without per-provider SDKs. Target selection:
 * - `config.remoteName` set → that remote from [RcloneConfigStore] (any type), below `basePath`;
 * - S3 without a remote → on-the-fly `:s3,provider=..,region=..,endpoint=..:` with the access key
 * pair from the vault ([Secret.Password]: username = access key id, password = secret key)
 * passed as `RCLONE_S3_ACCESS_KEY_ID` / `RCLONE_S3_SECRET_ACCESS_KEY` env vars; `basePath`
 * starts with the bucket. `options` keys `provider`, `region`, `endpoint` and `rclone.<opt>`
 * become (non-secret) connection-string parameters;
 * - MEGA without a remote → `:mega:` with `RCLONE_MEGA_USER` and the rclone-obscured
 * `RCLONE_MEGA_PASS` env vars ([Secret.Password]; username falls back to `config.username`).
 */
@Singleton
class RcloneBackend @Inject constructor(
    private val cli: RcloneCli,
    private val configStore: RcloneConfigStore,
    private val vault: CredentialVault,
) : StorageBackend {

    override val types: Set<DestinationType> = setOf(DestinationType.RCLONE, DestinationType.S3, DestinationType.MEGA)

    override suspend fun connect(destination: Destination): StorageSession {
        if (destination.type !in types) {
            throw StorageException(StorageException.Reason.UNSUPPORTED, "RcloneBackend cannot serve ${destination.type}")
        }
        val target = resolveTarget(destination)
        val runner = cli.runner()
        val lease = configStore.acquire()
        DftbLog.d(TAG, "connect ${destination.id} via ${runner.description} -> $target")
        return RcloneSession(destination, runner, target, lease, configStore, cli.commonFlags(lease.configPath))
    }

    private suspend fun resolveTarget(destination: Destination): RcloneTarget {
        val config = destination.config
        val remoteName = config.remoteName?.trim()?.takeIf { it.isNotEmpty() }
        if (remoteName != null) {
            val section = configStore.document().section(remoteName)
                ?: throw StorageException(StorageException.Reason.MISCONFIGURED, "rclone remote '$remoteName' is not defined in rclone.conf")
            return RcloneTarget.named(remoteName, config.basePath, section.type)
        }
        return when (destination.type) {
            DestinationType.RCLONE -> throw StorageException(StorageException.Reason.MISCONFIGURED, "rclone destination has no remote name")
            DestinationType.S3 -> {
                val secret = loadPassword(destination)
                RcloneTargets.s3(config, accessKeyId = secret.username ?: config.username, secretAccessKey = secret.password)
            }
            DestinationType.MEGA -> {
                val secret = loadPassword(destination)
                val user = secret.username ?: config.username
                    ?: throw StorageException(StorageException.Reason.MISCONFIGURED, "MEGA destination has no account e-mail")
                RcloneTargets.mega(config, user, cli.obscure(secret.password))
            }
            else -> throw StorageException(StorageException.Reason.UNSUPPORTED, "RcloneBackend cannot serve ${destination.type}")
        }
    }

    private suspend fun loadPassword(destination: Destination): Secret.Password {
        val ref = destination.credentialRef
            ?: throw StorageException(StorageException.Reason.AUTH, "${destination.type} destination has no stored credential")
        val secret = try {
            vault.load(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultException) {
            throw StorageException(StorageException.Reason.AUTH, "Credential vault cannot read the ${destination.type} credential (${e.reason})", e)
        }
        return secret as? Secret.Password
            ?: throw StorageException(StorageException.Reason.AUTH, "${destination.type} credential is missing or not a key/password pair")
    }

    private companion object {
        const val TAG = "RcloneBackend"
    }
}

/** Pure builders for on-the-fly targets, separate from the backend so they are unit-tested. */
object RcloneTargets {

    private const val PASSTHROUGH_PREFIX = "rclone."

    fun s3(config: DestinationConfig, accessKeyId: String?, secretAccessKey: String): RcloneTarget {
        val base = RclonePaths.normalize(config.basePath)
        if (base.isEmpty()) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, "S3 destination needs a base path starting with the bucket name")
        }
        if (accessKeyId.isNullOrBlank() || secretAccessKey.isBlank()) {
            throw StorageException(StorageException.Reason.AUTH, "S3 destination needs an access key id and secret access key")
        }
        val endpoint = config.options["endpoint"]?.takeIf { it.isNotBlank() }
            ?: config.host?.takeIf { it.isNotBlank() }?.let { host -> config.port?.let { "$host:$it" } ?: host }
        val params = LinkedHashMap<String, String>()
        params["provider"] = config.options["provider"]?.takeIf { it.isNotBlank() } ?: if (endpoint == null) "AWS" else "Other"
        config.options["region"]?.takeIf { it.isNotBlank() }?.let { params["region"] = it }
        endpoint?.let { params["endpoint"] = it }
        val (extraParams, extraSecrets) = passthrough(config)
        params.putAll(extraParams)
        return RcloneTarget.onTheFly(
            backend = "s3",
            params = params,
            secrets = extraSecrets + mapOf("access_key_id" to accessKeyId, "secret_access_key" to secretAccessKey),
            basePath = base,
        )
    }

    fun mega(config: DestinationConfig, user: String, obscuredPassword: String): RcloneTarget {
        val (extraParams, extraSecrets) = passthrough(config)
        return RcloneTarget.onTheFly(
            backend = "mega",
            params = extraParams,
            secrets = extraSecrets + mapOf("user" to user, "pass" to obscuredPassword),
            basePath = config.basePath,
        )
    }

    private val OPTION_KEY = Regex("^[a-z0-9_]+$")

    /**
     * `options["rclone.<opt>"]` → (connection-string params, env secrets). Secret-looking options
     * (e.g. `sse_customer_key`) go to the environment so they never reach argv or logs.
     */
    private fun passthrough(config: DestinationConfig): Pair<Map<String, String>, Map<String, String>> {
        val params = LinkedHashMap<String, String>()
        val secrets = LinkedHashMap<String, String>()
        config.options.forEach { (rawKey, value) ->
            if (!rawKey.startsWith(PASSTHROUGH_PREFIX)) return@forEach
            val key = rawKey.removePrefix(PASSTHROUGH_PREFIX)
            if (!OPTION_KEY.matches(key)) {
                throw StorageException(StorageException.Reason.MISCONFIGURED, "Option '$rawKey' is not a valid rclone option name")
            }
            if (RcloneIni.isSecretKey(key)) secrets[key] = value else params[key] = value
        }
        return params to secrets
    }
}

/**
 * An open rclone destination. Each call is one rclone invocation against [target]; the config
 * [lease] keeps rclone.conf materialized until [close]. Streams take their own lease so a
 * transfer outlives an early session close safely.
 */
class RcloneSession internal constructor(
    override val destination: Destination,
    private val runner: RcloneProcessRunner,
    private val target: RcloneTarget,
    private val lease: RcloneConfigStore.Lease,
    private val configStore: RcloneConfigStore,
    private val flags: List<String>,
) : StorageSession {

    @Volatile private var closed = false

    override suspend fun spaceInfo(): SpaceInfo? {
        val result = invoke("about", "--json", target.spec(""))
        if (result.isSuccess) return RcloneJson.parseAbout(result.stdout)
        val error = failure("about", result)
        if (error.reason == StorageException.Reason.UNSUPPORTED) return null
        throw error
    }

    override suspend fun list(path: String): List<RemoteEntry> {
        val result = invoke("lsjson", target.spec(path))
        if (!result.isSuccess) throw failure("lsjson", result)
        return RcloneJson.parseList(result.stdout, path)
    }

    override suspend fun stat(path: String): RemoteEntry? {
        val result = invoke("lsjson", "--stat", target.spec(path))
        if (result.isSuccess) return RcloneJson.parseStat(result.stdout, path)
        val error = failure("lsjson --stat", result)
        if (error.reason == StorageException.Reason.NOT_FOUND) return null
        throw error
    }

    override suspend fun mkdirs(path: String) {
        val result = invoke("mkdir", target.spec(path))
        if (!result.isSuccess) throw failure("mkdir", result)
    }

    override suspend fun openWrite(path: String, append: Boolean): OutputStream {
        if (append) throw StorageException(StorageException.Reason.UNSUPPORTED, "rclone uploads cannot be appended to; restart the upload")
        requireFilePath(path)
        ensureOpen()
        val streamLease = configStore.acquire()
        return try {
            runner.openUpload(uploadArgs(path), target.environment) { streamLease.close() }
        } catch (e: Throwable) {
            streamLease.close()
            throw e
        }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream {
        require(offset >= 0) { "offset must be >= 0" }
        requireFilePath(path)
        val entry = stat(path) ?: throw StorageException(StorageException.Reason.NOT_FOUND, "Not found: $path")
        if (entry.isDirectory) throw StorageException(StorageException.Reason.NOT_FOUND, "Is a directory, not a file: $path")
        val streamLease = configStore.acquire()
        val args = buildList {
            add("cat")
            addAll(flags)
            if (offset > 0) {
                add("--offset")
                add(offset.toString())
            }
            add(target.spec(path))
        }
        return try {
            runner.openDownload(args, target.environment) { streamLease.close() }
        } catch (e: Throwable) {
            streamLease.close()
            throw e
        }
    }

    override suspend fun delete(path: String) {
        requireFilePath(path)
        val entry = stat(path) ?: return
        val op = if (entry.isDirectory) "rmdir" else "deletefile"
        val result = invoke(op, target.spec(path))
        if (result.isSuccess) return
        val error = failure(op, result)
        if (error.reason != StorageException.Reason.NOT_FOUND) throw error
    }

    override suspend fun rename(from: String, to: String) {
        requireFilePath(from)
        requireFilePath(to)
        val result = invoke("moveto", target.spec(from), target.spec(to))
        if (!result.isSuccess) throw failure("moveto", result)
    }

    override fun close() {
        if (closed) return
        closed = true
        lease.close()
    }

    /** `rcat` argv: common flags, backend upload tuning, destination. */
    internal fun uploadArgs(path: String): List<String> = listOf("rcat") + flags + target.uploadFlags + target.spec(path)

    private suspend fun invoke(subcommand: String, vararg args: String): RcloneResult {
        ensureOpen()
        return runner.run(listOf(subcommand) + flags + args.toList(), target.environment)
    }

    private fun failure(operation: String, result: RcloneResult): StorageException =
        if (result.timedOut) {
            StorageException(StorageException.Reason.TIMEOUT, "rclone $operation timed out")
        } else {
            RcloneErrorMapper.toException(operation, result.exitCode, result.logLines)
        }

    private fun ensureOpen() {
        if (closed) throw StorageException(StorageException.Reason.PROTOCOL, "rclone session for ${destination.id} is closed")
    }

    private fun requireFilePath(path: String) {
        if (RclonePaths.normalize(path).isEmpty()) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, "Operation needs a path below the destination base")
        }
    }
}
