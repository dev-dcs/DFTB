package ca.devdcs.backup.core.storage

import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.repository.DestinationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal fun testDestination(type: DestinationType = DestinationType.LOCAL_INTERNAL, id: String = "d1") =
    Destination(id = id, name = "Test", type = type, createdAt = 1L, updatedAt = 1L)

/** Session whose behaviour is scripted by lambdas; records whether it was closed. */
internal class FakeSession(
    override val destination: Destination,
    private val space: suspend () -> SpaceInfo? = { null },
    private val lister: suspend (String) -> List<RemoteEntry> = { emptyList() },
) : StorageSession {
    var closed = false
        private set

    override suspend fun spaceInfo(): SpaceInfo? = space()
    override suspend fun list(path: String): List<RemoteEntry> = lister(path)
    override suspend fun stat(path: String): RemoteEntry? = null
    override suspend fun mkdirs(path: String) = Unit
    override suspend fun openWrite(path: String, append: Boolean): OutputStream = ByteArrayOutputStream()
    override suspend fun openRead(path: String, offset: Long): InputStream = ByteArrayInputStream(ByteArray(0))
    override suspend fun delete(path: String) = Unit
    override suspend fun rename(from: String, to: String) = Unit
    override fun close() {
        closed = true
    }
}

internal class FakeBackend(
    override val types: Set<DestinationType>,
    private val onConnect: suspend (Destination) -> StorageSession,
) : StorageBackend {
    override suspend fun connect(destination: Destination): StorageSession = onConnect(destination)
}

internal class FakeDestinationRepository : DestinationRepository {
    data class Verification(val id: String, val at: Long, val free: Long?, val total: Long?)

    val verifications = mutableListOf<Verification>()
    private val store = MutableStateFlow<Map<String, Destination>>(emptyMap())

    override fun observeAll(): Flow<List<Destination>> = store.map { it.values.toList() }
    override fun observe(id: String): Flow<Destination?> = store.map { it[id] }
    override fun observeCount(): Flow<Int> = store.map { it.size }
    override suspend fun getAll(): List<Destination> = store.value.values.toList()
    override suspend fun get(id: String): Destination? = store.value[id]
    override suspend fun getByType(type: DestinationType): List<Destination> =
        store.value.values.filter { it.type == type }
    override suspend fun save(destination: Destination) {
        store.value = store.value + (destination.id to destination)
    }
    override suspend fun saveAll(destinations: List<Destination>) = destinations.forEach { save(it) }
    override suspend fun recordVerification(id: String, verifiedAt: Long, freeBytes: Long?, totalBytes: Long?) {
        verifications += Verification(id, verifiedAt, freeBytes, totalBytes)
    }
    override suspend fun delete(id: String) {
        store.value = store.value - id
    }
}
