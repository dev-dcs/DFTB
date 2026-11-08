package ca.devdcs.backup.ui.settings

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.BuildConfig
import ca.devdcs.backup.R
import ca.devdcs.backup.core.credits.OpenSourceCredit
import ca.devdcs.backup.core.credits.OpenSourceCredits
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.ExpandableCard
import ca.devdcs.backup.ui.licenses.ExternalLinks
import ca.devdcs.backup.ui.mapping.label
import kotlinx.coroutines.launch

/**
 * Version, build and operating mode, plus the ways out to the full licenses list, the device
 * overview and every credited project's source repository (from the shared credits dataset, so
 * About and onboarding can never disagree).
 */
@Composable
internal fun AboutSection(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onOpenLicenses: () -> Unit,
    onOpenSystemInfo: () -> Unit,
    viewModel: GeneralSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectSettingsMessages(viewModel.messages, snackbarHostState)
    val context = LocalContext.current
    val launchContext = LocalActivity.current ?: context
    val scope = rememberCoroutineScope()
    val grouped = remember { OpenSourceCredits.groupedByKind() }
    val openSource: (OpenSourceCredit) -> Unit = { credit ->
        if (!ExternalLinks.openUrl(launchContext, credit.sourceUrl)) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.onboarding_no_app_for_link)) }
        }
    }

    SettingsList(contentPadding = contentPadding) {
        item(key = "abou_about_app") {
            SectionCard(
                title = BuildConfig.APP_DISPLAY_NAME,
                subtitle = stringResource(R.string.settings_about_tagline),
                icon = Icons.Outlined.Info,
            ) {
                KeyValueRow(
                    label = stringResource(R.string.app_version),
                    value = stringResource(R.string.settings_about_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                )
                KeyValueRow(label = stringResource(R.string.app_build_type), value = BuildConfig.BUILD_TYPE)
                KeyValueRow(label = stringResource(R.string.app_package), value = context.packageName)
                KeyValueRow(
                    label = stringResource(R.string.mode_section_title),
                    value = state.operatingMode?.let { stringResource(it.labelRes) } ?: stringResource(R.string.settings_mode_none),
                )
            }
        }
        item(key = "abou_about_links") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onOpenLicenses) {
                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.licenses_title), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
                OutlinedButton(onClick = onOpenSystemInfo) {
                    Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.system_info_title), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
        item(key = "abou_about_sources") {
            ExpandableCard(
                title = stringResource(R.string.settings_about_sources_title),
                subtitle = pluralStringResource(R.plurals.settings_about_sources_count, OpenSourceCredits.all.size, OpenSourceCredits.all.size),
            ) {
                grouped.forEach { (kind, credits) ->
                    Text(
                        kind.label(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    credits.forEach { credit -> SourceRow(credit = credit, onOpen = { openSource(credit) }) }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(credit: OpenSourceCredit, onOpen: () -> Unit) {
    val host = remember(credit.sourceUrl) { Uri.parse(credit.sourceUrl).host ?: credit.sourceUrl }
    val openDescription = stringResource(R.string.settings_about_source_open_cd, credit.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = openDescription, onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
        ) {
            Text(credit.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOfNotNull(host, credit.license.spdxId, credit.version).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = openDescription, modifier = Modifier.size(18.dp))
    }
}
