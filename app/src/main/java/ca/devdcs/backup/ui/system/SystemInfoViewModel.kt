package ca.devdcs.backup.ui.system

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.BuildConfig
import ca.devdcs.backup.core.locale.AppLanguage
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.nativebridge.DftbNative
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.core.preferences.ThemeMode
import ca.devdcs.backup.core.usb.UsbAttachEvents
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class VolumeInfo(
    val path: String,
    val totalBytes: Long,
    val availableBytes: Long,
    val viaNative: Boolean,
)

data class SystemInfoUiState(
    val language: AppLanguage = AppLanguage.DEFAULT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val operatingMode: OperatingMode? = null,
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidRelease: String = Build.VERSION.RELEASE,
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val securityPatch: String = Build.VERSION.SECURITY_PATCH,
    val supportedAbis: String = Build.SUPPORTED_ABIS.joinToString(", "),
    val kernelVersion: String = System.getProperty("os.version") ?: "",
    val internalVolume: VolumeInfo? = null,
    val externalVolume: VolumeInfo? = null,
    val nativeLoaded: Boolean = false,
    val nativeVersion: String? = null,
    val processUid: Int? = null,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val appPackage: String = BuildConfig.APPLICATION_ID,
    val buildType: String = BuildConfig.BUILD_TYPE,
)

@HiltViewModel
class SystemInfoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferencesRepository,
    private val localeManager: LocaleManager,
    private val native: DftbNative,
    usbAttachEvents: UsbAttachEvents,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val volumes = MutableStateFlow<Pair<VolumeInfo?, VolumeInfo?>>(null to null)

    /** Human-readable name of a freshly attached USB device, for a snackbar. */
    val usbAttached: Flow<String> = usbAttachEvents.events
        .map { device -> device.productName ?: device.manufacturerName ?: device.deviceName }

    val uiState: StateFlow<SystemInfoUiState> = combine(
        localeManager.language,
        preferences.preferences,
        volumes,
    ) { language, prefs, (internal, external) ->
        SystemInfoUiState(
            language = language,
            themeMode = prefs.themeMode,
            dynamicColor = prefs.dynamicColor,
            operatingMode = prefs.operatingMode,
            internalVolume = internal,
            externalVolume = external,
            nativeLoaded = native.isLoaded,
            nativeVersion = native.version(),
            processUid = native.processUid(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SystemInfoUiState(language = localeManager.language.value))

    init {
        refreshStorage()
    }

    fun setLanguage(language: AppLanguage) = localeManager.setLanguage(language)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferences.setDynamicColor(enabled) }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                val internal = volumeInfo(context.dataDir.absolutePath)
                val external = volumeInfo(Environment.getExternalStorageDirectory().absolutePath)
                internal to external
            }
            volumes.value = result
        }
    }

    /** Prefer statvfs through JNI (exact, works on any mount point); fall back to StatFs. */
    private fun volumeInfo(path: String): VolumeInfo? {
        native.filesystemStats(path)?.let {
            return VolumeInfo(path, totalBytes = it.totalBytes, availableBytes = it.availableBytes, viaNative = true)
        }
        return try {
            val stat = StatFs(path)
            VolumeInfo(path, totalBytes = stat.totalBytes, availableBytes = stat.availableBytes, viaNative = false)
        } catch (error: IllegalArgumentException) {
            null
        }
    }
}
