package ca.devdcs.backup.core.storage

import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StorageRegistry] over the Hilt-multibound set of [StorageBackend]s.
 *
 * Exists so protocol modules contribute themselves with `@Binds @IntoSet` and nobody keeps a
 * hand-written switch over [DestinationType]. Two backends claiming one type is a wiring bug, so
 * construction fails fast instead of silently picking one.
 */
@Singleton
class DefaultStorageRegistry @Inject constructor(
    backends: Set<@JvmSuppressWildcards StorageBackend>,
) : StorageRegistry {

    private val byType: Map<DestinationType, StorageBackend> = index(backends)

    /** Types some backend serves; lets the UI hide destination kinds this build cannot reach. */
    val supportedTypes: Set<DestinationType> get() = byType.keys

    override fun backendFor(type: DestinationType): StorageBackend =
        byType[type] ?: throw StorageException(
            StorageException.Reason.UNSUPPORTED,
            "No storage backend is installed for ${type.storageKey}",
        )

    override suspend fun <T> withSession(destination: Destination, block: suspend (StorageSession) -> T): T {
        val session = backendFor(destination.type).connect(destination)
        try {
            return block(session)
        } finally {
            session.close()
        }
    }

    private companion object {
        fun index(backends: Set<StorageBackend>): Map<DestinationType, StorageBackend> {
            val map = HashMap<DestinationType, StorageBackend>()
            for (backend in backends) {
                for (type in backend.types) {
                    val previous = map.put(type, backend)
                    check(previous == null) {
                        "Destination type $type is claimed by both ${previous!!.javaClass.name} " +
                            "and ${backend.javaClass.name}; each type must have exactly one StorageBackend"
                    }
                }
            }
            return map
        }
    }
}
