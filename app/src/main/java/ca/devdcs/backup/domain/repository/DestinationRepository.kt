package ca.devdcs.backup.domain.repository

import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import kotlinx.coroutines.flow.Flow

/** Persistence boundary for destinations (non-secret settings only; secrets live in the vault). */
interface DestinationRepository {
    fun observeAll(): Flow<List<Destination>>
    fun observe(id: String): Flow<Destination?>
    fun observeCount(): Flow<Int>
    suspend fun getAll(): List<Destination>
    suspend fun get(id: String): Destination?
    suspend fun getByType(type: DestinationType): List<Destination>
    /** Insert or replace by id. */
    suspend fun save(destination: Destination)
    suspend fun saveAll(destinations: List<Destination>)
    /** Records the outcome of a connectivity/capacity probe without rewriting the whole row. */
    suspend fun recordVerification(id: String, verifiedAt: Long, freeBytes: Long?, totalBytes: Long?)
    suspend fun delete(id: String)
}
