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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R

private val disclaimerPoints: List<Int> = listOf(
    R.string.onboarding_disclaimer_partitions,
    R.string.onboarding_disclaimer_verify,
    R.string.onboarding_disclaimer_passwords,
    R.string.onboarding_disclaimer_elevation,
    R.string.onboarding_disclaimer_cloud,
    R.string.onboarding_disclaimer_warranty,
)

/**
 * Step 3. The acknowledgment is recorded with a timestamp at the end of the wizard; the Next button
 * stays disabled until the box is ticked so the user cannot skip past it by accident.
 */
@Composable
fun DisclaimerStep(accepted: Boolean, onAcceptedChange: (Boolean) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = StepContentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StepHeader(
                titleRes = R.string.onboarding_disclaimer_title,
                descriptionRes = R.string.onboarding_disclaimer_description,
                icon = Icons.Outlined.Gavel,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    disclaimerPoints.forEach { pointRes -> BulletText(stringResource(pointRes)) }
                }
            }
        }
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = accepted, role = Role.Checkbox, onValueChange = onAcceptedChange)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = accepted, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.onboarding_disclaimer_accept),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
