package ca.devdcs.backup.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R

/** One capability card on the overview page. */
private class Feature(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
)

private val features: List<Feature> = listOf(
    Feature(Icons.Outlined.SdStorage, R.string.onboarding_feature_partitions_title, R.string.onboarding_feature_partitions_desc),
    Feature(Icons.Outlined.Apps, R.string.onboarding_feature_apps_title, R.string.onboarding_feature_apps_desc),
    Feature(Icons.Outlined.Folder, R.string.onboarding_feature_files_title, R.string.onboarding_feature_files_desc),
    Feature(Icons.Outlined.Cloud, R.string.onboarding_feature_destinations_title, R.string.onboarding_feature_destinations_desc),
    Feature(Icons.Outlined.Compress, R.string.onboarding_feature_pipelines_title, R.string.onboarding_feature_pipelines_desc),
    Feature(Icons.Outlined.SettingsBackupRestore, R.string.onboarding_feature_twrp_title, R.string.onboarding_feature_twrp_desc),
    Feature(Icons.Outlined.Restore, R.string.onboarding_feature_restore_title, R.string.onboarding_feature_restore_desc),
    Feature(Icons.Outlined.Schedule, R.string.onboarding_feature_scheduling_title, R.string.onboarding_feature_scheduling_desc),
)

/** Step 2. A read-only tour of what the app does, so later choices (mode, format) have context. */
@Composable
fun OverviewStep() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = StepContentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StepHeader(
                titleRes = R.string.onboarding_overview_title,
                descriptionRes = R.string.onboarding_overview_description,
                icon = Icons.Outlined.Lightbulb,
            )
        }
        items(features, key = { it.titleRes }) { feature -> FeatureCard(feature) }
    }
}

@Composable
private fun FeatureCard(feature: Feature) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(feature.titleRes), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(feature.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
