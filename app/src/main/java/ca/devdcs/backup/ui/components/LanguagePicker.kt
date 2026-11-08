package ca.devdcs.backup.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.core.locale.AppLanguage

/**
 * Seven chips, each labelled in its own language. Selecting one calls [onSelected]; the caller
 * routes it to LocaleManager and the whole tree re-renders in the new language on the next frame.
 */
@Composable
fun LanguagePicker(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppLanguage.entries.forEach { language ->
            val isSelected = language == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelected(language) },
                label = { Text(language.nativeName) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}
