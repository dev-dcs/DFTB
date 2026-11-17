package ca.devdcs.backup.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatTextdirectionRToL
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.preferences.ThemeMode
import ca.devdcs.backup.ui.components.LanguagePicker
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.theme.DftbTheme
import ca.devdcs.backup.ui.theme.resolvesToDark
import ca.devdcs.backup.ui.theme.supportsDynamicColor

/**
 * Live language picker. Reuses the onboarding [LanguagePicker] and LocaleManager, so a tap
 * re-renders the whole app (including this page, and right-to-left mirroring for Arabic) on the
 * next frame without restarting the Activity.
 */
@Composable
internal fun LanguageSection(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: GeneralSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectSettingsMessages(viewModel.messages, snackbarHostState)
    SettingsList(contentPadding = contentPadding) {
        item(key = "gene_language_picker") {
            SectionCard(
                title = stringResource(R.string.language_section_title),
                subtitle = stringResource(R.string.language_section_description),
                icon = Icons.Outlined.Language,
            ) {
                LanguagePicker(selected = state.language, onSelected = viewModel::setLanguage, modifier = Modifier.fillMaxWidth())
            }
        }
        item(key = "gene_language_current") {
            SectionCard(title = stringResource(R.string.settings_language_current_title)) {
                Text(
                    text = stringResource(R.string.settings_language_current, state.language.nativeName, state.language.englishName),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.language.isRtl) {
                    StatusLine(
                        icon = Icons.AutoMirrored.Outlined.FormatTextdirectionRToL,
                        tint = MaterialTheme.colorScheme.primary,
                        text = stringResource(R.string.settings_language_rtl_active),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_language_scope_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Theme mode, Material You toggle and a side-by-side preview of the light and dark schemes the
 * current choices produce. The preview renders real [DftbTheme] instances so it is exactly what
 * the app will look like, not an illustration.
 */
@Composable
internal fun AppearanceSection(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: GeneralSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectSettingsMessages(viewModel.messages, snackbarHostState)
    val activeDark = state.themeMode.resolvesToDark()
    SettingsList(contentPadding = contentPadding) {
        item(key = "gene_appearance_theme") {
            SectionCard(title = stringResource(R.string.theme_mode_label), icon = Icons.Outlined.Palette) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                            icon = { Icon(mode.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                            label = { Text(stringResource(mode.labelRes)) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_appearance_theme_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "gene_appearance_color") {
            SectionCard(title = stringResource(R.string.settings_appearance_color_title)) {
                SettingsSwitchRow(
                    label = stringResource(R.string.dynamic_color_label),
                    checked = state.dynamicColor && supportsDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                    enabled = supportsDynamicColor,
                    hint = stringResource(
                        if (supportsDynamicColor) R.string.settings_appearance_dynamic_hint else R.string.dynamic_color_unsupported,
                    ),
                )
            }
        }
        item(key = "gene_appearance_preview") {
            SectionCard(
                title = stringResource(R.string.settings_appearance_preview_title),
                subtitle = stringResource(R.string.settings_appearance_preview_subtitle),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemePreview(
                        dark = false,
                        dynamicColor = state.dynamicColor,
                        active = !activeDark,
                        label = stringResource(R.string.theme_mode_light),
                    )
                    ThemePreview(
                        dark = true,
                        dynamicColor = state.dynamicColor,
                        active = activeDark,
                        label = stringResource(R.string.theme_mode_dark),
                    )
                }
            }
        }
    }
}

private fun ThemeMode.icon(): ImageVector = when (this) {
    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
}

/**
 * A miniature screen rendered under its own [DftbTheme]. Purely visual: semantics are replaced by
 * one description so TalkBack announces "Light theme preview, in use" instead of fake controls.
 */
@Composable
private fun ThemePreview(dark: Boolean, dynamicColor: Boolean, active: Boolean, label: String) {
    val outer = MaterialTheme.colorScheme
    val description = if (active) {
        stringResource(R.string.settings_appearance_preview_active_cd, label)
    } else {
        stringResource(R.string.settings_appearance_preview_cd, label)
    }
    OutlinedCard(
        modifier = Modifier
            .widthIn(min = 150.dp, max = 220.dp)
            .clearAndSetSemantics { contentDescription = description },
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) outer.primary else outer.outlineVariant),
    ) {
        DftbTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = dynamicColor) {
            val scheme = MaterialTheme.colorScheme
            Surface(color = scheme.background, contentColor = scheme.onBackground) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Surface(color = scheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PreviewBar(color = scheme.onSurfaceVariant, fraction = 0.7f)
                            PreviewBar(color = scheme.outline, fraction = 0.45f)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Swatch(scheme.primary)
                        Swatch(scheme.secondary)
                        Swatch(scheme.tertiary)
                        Swatch(scheme.error)
                    }
                    Surface(color = scheme.primary, contentColor = scheme.onPrimary, shape = CircleShape) {
                        Text(
                            stringResource(R.string.settings_appearance_preview_button),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    if (active) {
                        Text(
                            stringResource(R.string.settings_appearance_preview_in_use),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(20.dp)) {}
}

/** A text-line placeholder in the preview, drawn with a scheme color. */
@Composable
private fun PreviewBar(color: Color, fraction: Float) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(6.dp),
    ) {}
}
