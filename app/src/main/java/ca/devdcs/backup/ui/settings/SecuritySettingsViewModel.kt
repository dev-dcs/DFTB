package ca.devdcs.backup.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.ssh.SshKnownHosts
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.repository.ProfileRepository
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.VaultEntryInfo
import ca.devdcs.backup.domain.security.VaultException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** Loading state of one list on the security page. */
sealed interface ListLoad<out T> {
    data object Loading : ListLoad<Nothing>
    data class Loaded<T>(val items: List<T>) : ListLoad<T>

    /** The store could not be read; [vaultReason] is set when the vault itself refused. */
    data class Failed(val vaultReason: VaultException.Reason? = null) : ListLoad<Nothing>
}

/** Security page state. [busyRefs] marks rows with a delete/forget in flight. */
data class SecuritySettingsUiState(
    val keys: ListLoad<EncryptionKeyRow> = ListLoad.Loading,
    val knownHosts: ListLoad<KnownHostGroup> = ListLoad.Loading,
    val busyRefs: Set<String> = emptySet(),
)

/**
 * Vault encryption keys and pinned SSH host keys. Key deletion is guarded twice: the screen only
 * offers it for keys no profile references, and [deleteKey] re-checks against a fresh profile
 * list immediately before deleting, so a profile created meanwhile still blocks it.
 */
@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(
    private val vault: CredentialVault,
    private val profileRepository: ProfileRepository,
    private val knownHosts: SshKnownHosts,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val vaultEntries = MutableStateFlow<ListLoad<VaultEntryInfo>>(ListLoad.Loading)
    private val hosts = MutableStateFlow<ListLoad<KnownHostGroup>>(ListLoad.Loading)
    private val busy = MutableStateFlow<Set<String>>(emptySet())

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    private val profiles = profileRepository.observeAll().catch { error ->
        DftbLog.w(TAG, "Profiles unavailable", error)
        emit(emptyList())
    }

    val uiState: StateFlow<SecuritySettingsUiState> =
        combine(vaultEntries, profiles, hosts, busy) { entries, profileList, hostList, busyRefs ->
            SecuritySettingsUiState(
                keys = when (entries) {
                    ListLoad.Loading -> ListLoad.Loading
                    is ListLoad.Failed -> entries
                    is ListLoad.Loaded -> ListLoad.Loaded(encryptionKeyRows(entries.items, profileList))
                },
                knownHosts = hostList,
                busyRefs = busyRefs,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SecuritySettingsUiState())

    init {
        refresh()
    }

    fun refresh() {
        loadKeys()
        loadKnownHosts()
    }

    fun deleteKey(ref: String) {
        if (ref in busy.value) return
        busy.update { it + ref }
        viewModelScope.launch {
            try {
                val verdict = KeyDeletionGuard.check(ref, profileRepository.getAll())
                if (verdict is KeyDeletionVerdict.Blocked) {
                    _messages.trySend(
                        SettingsMessage.text(R.string.settings_security_key_in_use, verdict.references.joinToString(", ") { it.profileName }),
                    )
                    return@launch
                }
                val deleted = vault.delete(ref)
                DftbLog.i(TAG, "Encryption key deleted by the user: ${ref.take(REF_LOG_CHARS)}… (existed=$deleted)")
                _messages.trySend(SettingsMessage(if (deleted) R.string.settings_security_key_deleted else R.string.settings_security_key_already_gone))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Key deletion failed", e)
                _messages.trySend(SettingsMessage(R.string.settings_security_key_delete_failed))
            } finally {
                busy.update { it - ref }
                loadKeys()
            }
        }
    }

    fun forgetHost(group: KnownHostGroup) {
        val endpoint = group.endpoint
        if (endpoint == null) {
            _messages.trySend(SettingsMessage(R.string.settings_security_host_unparsable))
            return
        }
        if (group.hostId in busy.value) return
        busy.update { it + group.hostId }
        viewModelScope.launch {
            try {
                val removed = withContext(ioDispatcher) { knownHosts.forget(endpoint.host, endpoint.port) }
                _messages.trySend(
                    if (removed) {
                        SettingsMessage.text(R.string.settings_security_host_forgotten, group.hostId)
                    } else {
                        SettingsMessage(R.string.settings_security_host_already_gone)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                DftbLog.w(TAG, "Could not update known hosts", e)
                _messages.trySend(SettingsMessage(R.string.settings_security_hosts_write_failed))
            } finally {
                busy.update { it - group.hostId }
                loadKnownHosts()
            }
        }
    }

    private fun loadKeys() {
        viewModelScope.launch {
            vaultEntries.value = try {
                ListLoad.Loaded(withContext(ioDispatcher) { vault.list() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultException) {
                DftbLog.w(TAG, "Vault listing failed: ${e.reason}", e)
                ListLoad.Failed(e.reason)
            } catch (e: Exception) {
                DftbLog.w(TAG, "Vault listing failed", e)
                ListLoad.Failed()
            }
        }
    }

    private fun loadKnownHosts() {
        viewModelScope.launch {
            hosts.value = try {
                ListLoad.Loaded(KnownHostIds.group(withContext(ioDispatcher) { knownHosts.entries() }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Corrupt or unreadable store: SSH connections fail closed until it is fixed.
                DftbLog.w(TAG, "Known hosts unreadable", e)
                ListLoad.Failed()
            }
        }
    }

    private companion object {
        const val TAG = "SettingsSecurity"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        /** Enough of a vault ref to correlate log lines without printing the whole handle. */
        const val REF_LOG_CHARS = 12
    }
}
