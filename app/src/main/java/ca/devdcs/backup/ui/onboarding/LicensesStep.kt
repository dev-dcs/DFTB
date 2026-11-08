package ca.devdcs.backup.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.ui.licenses.ExpandedCreditIdsSaver
import ca.devdcs.backup.ui.licenses.ExternalLinks
import ca.devdcs.backup.ui.licenses.openSourceCreditItems

/**
 * Step 4. Same list as [ca.devdcs.backup.ui.licenses.LicensesScreen], embedded in the wizard with
 * a header. Attribution is shown before the user grants any access so it is never buried.
 */
@Composable
fun LicensesStep(onMessage: (Int) -> Unit) {
    val context = LocalContext.current
    var expandedIds by rememberSaveable(stateSaver = ExpandedCreditIdsSaver) { mutableStateOf(emptySet()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = StepContentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            StepHeader(
                titleRes = R.string.onboarding_licenses_title,
                descriptionRes = R.string.onboarding_licenses_description,
                icon = Icons.Outlined.Code,
            )
        }
        openSourceCreditItems(
            expandedIds = expandedIds,
            onToggle = { id -> expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id },
            onOpenUrl = { url -> if (!ExternalLinks.openUrl(context, url)) onMessage(R.string.onboarding_no_app_for_link) },
        )
    }
}
