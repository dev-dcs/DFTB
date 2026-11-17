package ca.devdcs.backup.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.core.locale.AppLanguage
import ca.devdcs.backup.ui.components.LanguagePicker

/**
 * Step 1. Reuses the shared [LanguagePicker]; the tree re-renders in the chosen language on the
 * next frame through ProvideAppLocale, which is why this page needs no "apply" button.
 */
@Composable
fun LanguageStep(state: OnboardingUiState, onSelect: (AppLanguage) -> Unit) {
    val suggested = state.suggestedLanguage?.takeIf { it != state.language }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = StepContentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StepHeader(
                titleRes = R.string.onboarding_language_title,
                descriptionRes = R.string.onboarding_language_description,
                icon = Icons.Outlined.Language,
            )
        }
        item {
            LanguagePicker(selected = state.language, onSelected = onSelect, modifier = Modifier.fillMaxWidth())
        }
        if (suggested != null) {
            item { SuggestedLanguageCard(suggested = suggested, onSelect = onSelect) }
        }
    }
}

@Composable
private fun SuggestedLanguageCard(suggested: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.onboarding_language_suggested, suggested.nativeName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onSelect(suggested) }) {
                    Text(stringResource(R.string.onboarding_language_use_suggested, suggested.nativeName))
                }
            }
        }
    }
}
