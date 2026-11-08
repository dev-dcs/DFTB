package ca.devdcs.backup.ui.licenses

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.core.credits.OpenSourceCredit
import ca.devdcs.backup.core.credits.OpenSourceCredits
import ca.devdcs.backup.ui.mapping.label

/**
 * Saver for the set of expanded credit ids, so the list keeps its open cards across
 * `rememberSaveable` restoration. Shared by the onboarding step and [LicensesScreen].
 */
val ExpandedCreditIdsSaver: Saver<Set<String>, Any> = listSaver(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * Appends every [OpenSourceCredits] entry to a LazyColumn, grouped under one header per
 * [ca.devdcs.backup.core.credits.BundleKind]. A `LazyListScope` extension (rather than a composable
 * with its own LazyColumn) so callers can put their own header and footer items around it.
 */
fun LazyListScope.openSourceCreditItems(
    expandedIds: Set<String>,
    onToggle: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    OpenSourceCredits.groupedByKind().forEach { (kind, credits) ->
        item(key = "kind-${kind.name}") {
            Text(
                text = kind.label(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        items(credits, key = { it.id }) { credit ->
            OpenSourceCreditCard(
                credit = credit,
                expanded = credit.id in expandedIds,
                onToggle = { onToggle(credit.id) },
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

/**
 * One credited project: name, version pill and license on the collapsed face; description and
 * Source / License / Website buttons when expanded.
 */
@Composable
fun OpenSourceCreditCard(
    credit: OpenSourceCredit,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(onClick = onToggle, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(credit.name, style = MaterialTheme.typography.titleMedium)
                        if (credit.version != null) {
                            Spacer(Modifier.width(8.dp))
                            VersionPill(credit.version)
                        }
                    }
                    Text(
                        text = stringResource(credit.license.nameRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.licenses_collapse else R.string.licenses_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(credit.descriptionRes), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(onClick = { onOpenUrl(credit.sourceUrl) }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.licenses_source))
                        }
                        TextButton(onClick = { onOpenUrl(credit.license.url) }) {
                            Text(stringResource(R.string.licenses_license))
                        }
                        val website = credit.websiteUrl
                        if (website != null && website != credit.sourceUrl) {
                            TextButton(onClick = { onOpenUrl(website) }) {
                                Text(stringResource(R.string.licenses_website))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionPill(version: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = stringResource(R.string.licenses_version, version),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
