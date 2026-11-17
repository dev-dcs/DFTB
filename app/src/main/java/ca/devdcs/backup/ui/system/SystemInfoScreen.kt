package ca.devdcs.backup.ui.system

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.preferences.ThemeMode
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.LanguagePicker
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.theme.supportsDynamicColor

/**
 * Device and app overview reached from the dashboard's overflow menu: live language switching,
 * theme controls, device/storage facts via the native bridge, the persisted operating mode, and
 * entry points to destinations, discovery and the licenses. Every string on this screen
 * re-renders in place when a language chip is tapped.
 *
 * @param onBack pops back to the screen that opened it (it is a secondary screen since ).
 * @param onOpenLicenses navigates to the open-source licenses screen; attribution must stay
 * reachable after onboarding.
 */
@Composable
fun SystemInfoScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenDestinations: () -> Unit,
    onOpenDiscovery: () -> Unit,
    viewModel: SystemInfoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val usbMessage = stringResource(R.string.usb_device_attached)

    LaunchedEffect(Unit) {
        viewModel.usbAttached.collect { name ->
            snackbarHostState.showSnackbar(usbMessage.format(name))
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.shell_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LanguageCard(state, viewModel) }
            item { AppearanceCard(state, viewModel) }
            item { OperatingModeCard(state) }
            item { DeviceCard(state) }
            item { StorageCard(state) }
            item { DestinationsEntryCard(onOpenDestinations) }
            item { DiscoveryEntryCard(onOpenDiscovery) }
            item { NativeCard(state) }
            item { AppCard(state, onOpenLicenses) }
        }
    }
}

@Composable
private fun LanguageCard(state: SystemInfoUiState, viewModel: SystemInfoViewModel) {
    SectionCard(
        title = stringResource(R.string.language_section_title),
        subtitle = stringResource(R.string.language_section_description),
        icon = Icons.Outlined.Language,
    ) {
        LanguagePicker(selected = state.language, onSelected = viewModel::setLanguage)
    }
}

@Composable
private fun AppearanceCard(state: SystemInfoUiState, viewModel: SystemInfoViewModel) {
    SectionCard(title = stringResource(R.string.appearance_section_title), icon = Icons.Outlined.Palette) {
        Text(stringResource(R.string.theme_mode_label), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    label = { Text(stringResource(mode.labelRes)) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dynamic_color_label), style = MaterialTheme.typography.bodyLarge)
                if (!supportsDynamicColor) {
                    Text(
                        stringResource(R.string.dynamic_color_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.dynamicColor && supportsDynamicColor,
                enabled = supportsDynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
        }
    }
}

@Composable
private fun OperatingModeCard(state: SystemInfoUiState) {
    SectionCard(title = stringResource(R.string.mode_section_title), icon = Icons.Outlined.Security) {
        val mode = state.operatingMode
        if (mode == null) {
            Text(stringResource(R.string.mode_not_selected), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(mode.labelRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(mode.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCard(state: SystemInfoUiState) {
    SectionCard(title = stringResource(R.string.device_section_title), icon = Icons.Outlined.PhoneAndroid) {
        KeyValueRow(stringResource(R.string.device_model), state.deviceModel)
        KeyValueRow(stringResource(R.string.android_version), state.androidRelease)
        KeyValueRow(stringResource(R.string.api_level), state.apiLevel.toString())
        KeyValueRow(stringResource(R.string.security_patch), state.securityPatch)
        KeyValueRow(stringResource(R.string.supported_abis), state.supportedAbis)
        KeyValueRow(stringResource(R.string.kernel_version), state.kernelVersion)
    }
}

@Composable
private fun StorageCard(state: SystemInfoUiState) {
    SectionCard(title = stringResource(R.string.storage_section_title), icon = Icons.Outlined.Storage) {
        VolumeRow(stringResource(R.string.storage_internal), state.internalVolume)
        VolumeRow(stringResource(R.string.storage_external), state.externalVolume)
    }
}

@Composable
private fun VolumeRow(label: String, volume: VolumeInfo?) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (volume == null) {
            Text(stringResource(R.string.storage_unavailable), style = MaterialTheme.typography.bodyMedium)
        } else {
            val used = (volume.totalBytes - volume.availableBytes).coerceAtLeast(0)
            Text(
                stringResource(
                    R.string.storage_free_of_total,
                    Formatter.formatFileSize(context, volume.availableBytes),
                    Formatter.formatFileSize(context, volume.totalBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { if (volume.totalBytes > 0) used.toFloat() / volume.totalBytes else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                volume.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NativeCard(state: SystemInfoUiState) {
    SectionCard(title = stringResource(R.string.native_section_title), icon = Icons.Outlined.Memory) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = if (state.nativeLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (state.nativeLoaded) R.string.native_loaded else R.string.native_not_loaded))
        }
        KeyValueRow(stringResource(R.string.native_version), state.nativeVersion ?: "—")
        KeyValueRow(stringResource(R.string.process_uid), state.processUid?.toString() ?: "—")
    }
}

@Composable
private fun DestinationsEntryCard(onOpenDestinations: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.destinations_title),
        subtitle = stringResource(R.string.destinations_entry_subtitle),
        icon = Icons.Outlined.CloudUpload,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenDestinations) {
                Text(stringResource(R.string.destinations_entry_open))
            }
        }
    }
}

@Composable
private fun DiscoveryEntryCard(onOpenDiscovery: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.discovery_title),
        subtitle = stringResource(R.string.discovery_entry_subtitle),
        icon = Icons.Outlined.TravelExplore,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenDiscovery) {
                Text(stringResource(R.string.discovery_entry_open))
            }
        }
    }
}

@Composable
private fun AppCard(state: SystemInfoUiState, onOpenLicenses: () -> Unit) {
    SectionCard(title = stringResource(R.string.app_section_title), icon = Icons.Outlined.Info) {
        KeyValueRow(stringResource(R.string.app_version), state.appVersion)
        KeyValueRow(stringResource(R.string.app_package), state.appPackage)
        KeyValueRow(stringResource(R.string.app_build_type), state.buildType)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenLicenses) {
                Text(stringResource(R.string.licenses_title))
            }
        }
    }
}
