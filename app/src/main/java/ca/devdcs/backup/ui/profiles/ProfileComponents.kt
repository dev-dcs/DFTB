package ca.devdcs.backup.ui.profiles

import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.TargetSelection
import ca.devdcs.backup.ui.destinations.FormTextField
import ca.devdcs.backup.ui.destinations.SecureWindowEffect
import ca.devdcs.backup.ui.discovery.currentLocale
import ca.devdcs.backup.ui.discovery.labelRes
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

/*
 * Presentational pieces shared by the profiles list, the wizard and the schedules overview.
 * Colors come only from MaterialTheme; layouts use start/end so they mirror under RTL.
 */

/** Coarse width buckets the profiles screens lay out for. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/**
 * Width class of the hosting window from the Material window size classes. Without an Activity
 * (previews) the same 600 dp / 840 dp breakpoints are applied to the configuration width.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberWidthClass(): WidthClass {
    val activity = LocalActivity.current
    if (activity == null) {
        val width = LocalConfiguration.current.screenWidthDp
        return when {
            width >= EXPANDED_MIN_WIDTH_DP -> WidthClass.EXPANDED
            width >= MEDIUM_MIN_WIDTH_DP -> WidthClass.MEDIUM
            else -> WidthClass.COMPACT
        }
    }
    return when (calculateWindowSizeClass(activity).widthSizeClass) {
        WindowWidthSizeClass.Expanded -> WidthClass.EXPANDED
        WindowWidthSizeClass.Medium -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
}

private const val MEDIUM_MIN_WIDTH_DP = 600
private const val EXPANDED_MIN_WIDTH_DP = 840

/** Columns for card grids at [widthClass]. */
fun gridColumns(widthClass: WidthClass): Int = when (widthClass) {
    WidthClass.COMPACT -> 1
    WidthClass.MEDIUM -> 2
    WidthClass.EXPANDED -> 3
}

/** First day of the week in [locale] (Sunday in the US, Monday in most of Europe). */
fun firstDayOfWeek(locale: Locale): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

/** Resource-backed wording for schedule and retention summaries, rebuilt when the language changes. */
@Composable
fun rememberScheduleWording(): ScheduleWording {
    val context = LocalContext.current
    val locale = currentLocale()
    return remember(context, locale) { ResourceScheduleWording(context, locale) }
}

/** "3 partitions · 12 apps · 2 folders · Images, Videos", or "Nothing selected". */
@Composable
fun targetsSummary(targets: TargetSelection): String {
    val parts = buildList {
        val partitions = targets.partitions.size
        if (partitions > 0) add(pluralStringResource(R.plurals.profiles_count_partitions, partitions, partitions))
        val apps = targets.apps.size
        if (apps > 0) add(pluralStringResource(R.plurals.profiles_count_apps, apps, apps))
        val folders = targets.paths.size
        if (folders > 0) add(pluralStringResource(R.plurals.profiles_count_folders, folders, folders))
        if (targets.categories.isNotEmpty()) {
            add(targets.categories.sortedBy { it.ordinal }.map { stringResource(it.labelRes()) }.joinToString(", "))
        }
    }
    return if (parts.isEmpty()) stringResource(R.string.profiles_targets_none) else parts.joinToString(SEPARATOR)
}

/** Separator between summary fragments; a middle dot reads the same in LTR and RTL. */
const val SEPARATOR = " · "

/** Last backup line: status icon, status, relative time, and the failure message when there is one. */
@Composable
fun LastRunLine(record: HistoryRecord?, modifier: Modifier = Modifier) {
    if (record == null) {
        Text(
            stringResource(R.string.profiles_last_run_never),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val (icon, tint) = statusIcon(record.status)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            val whenText = DateUtils.getRelativeTimeSpanString(
                record.finishedAt ?: record.startedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            Text(
                stringResource(R.string.profiles_last_run, stringResource(record.status.profileLabelRes()), whenText),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val error = record.errorMessage
        if (!error.isNullOrBlank() && !record.isSuccessful) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@ReadOnlyComposable
private fun statusIcon(status: JobStatus): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        JobStatus.COMPLETED -> Icons.Outlined.CheckCircle to scheme.primary
        JobStatus.COMPLETED_WITH_WARNINGS -> Icons.Outlined.Warning to scheme.tertiary
        JobStatus.RUNNING -> Icons.Outlined.Sync to scheme.primary
        JobStatus.INTERRUPTED -> Icons.Outlined.Info to scheme.tertiary
        JobStatus.FAILED -> Icons.Outlined.ErrorOutline to scheme.error
        JobStatus.CANCELLED -> Icons.Outlined.Info to scheme.onSurfaceVariant
    }
}

/** A bullet list of draft problems (errors in the error color, warnings in the tertiary color). */
@Composable
fun ProblemLines(problems: List<DraftProblem>, modifier: Modifier = Modifier) {
    if (problems.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        problems.forEach { problem ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                val error = problem.isError
                Icon(
                    if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Warning,
                    contentDescription = stringResource(if (error) R.string.profiles_cd_error else R.string.profiles_cd_warning),
                    tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(problem.messageRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** A small icon + text hint line (explanations under options). */
@Composable
fun HintLine(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info, warning: Boolean = false) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = if (warning) stringResource(R.string.profiles_cd_warning) else null,
            tint = if (warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Label + hint on the start side, a switch on the end side; unlike SwitchRow it can be disabled. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Minimum typed passphrase length the prompt accepts. */
const val MIN_PASSPHRASE_LENGTH = 8

/**
 * Asks for the passphrase of [profileName] (twice, so a typo cannot make the backup unreadable) and
 * hands it over as a CharArray the caller must wipe. [resume] words it for continuing an
 * interrupted backup (which needs the passphrase that job started with). FLAG_SECURE while shown;
 * nothing is saved in instance state.
 */
@Composable
fun PassphraseDialog(profileName: String, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit, resume: Boolean = false) {
    SecureWindowEffect()
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val tooShort = passphrase.length < MIN_PASSPHRASE_LENGTH
    val mismatch = confirm != passphrase
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.profiles_passphrase_title, profileName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (resume) R.string.profiles_passphrase_body_resume else R.string.profiles_passphrase_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FormTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.profiles_passphrase_field),
                    secret = true,
                    error = if (attempted && tooShort) {
                        pluralStringResource(R.plurals.profiles_passphrase_too_short, MIN_PASSPHRASE_LENGTH, MIN_PASSPHRASE_LENGTH)
                    } else {
                        null
                    },
                )
                FormTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.profiles_passphrase_confirm),
                    secret = true,
                    error = if (attempted && mismatch) stringResource(R.string.profiles_passphrase_mismatch) else null,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                attempted = true
                if (!tooShort && !mismatch) {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    confirm = ""
                    onSubmit(chars)
                }
            }) { Text(stringResource(if (resume) R.string.profiles_passphrase_resume else R.string.profiles_passphrase_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_cancel)) } },
    )
}

/** Confirm dialog for destructive actions; [consequence] spells out what will happen. */
@Composable
fun ConfirmDialog(
    title: String,
    consequence: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(consequence, style = MaterialTheme.typography.bodyMedium)
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_cancel)) } },
    )
}
