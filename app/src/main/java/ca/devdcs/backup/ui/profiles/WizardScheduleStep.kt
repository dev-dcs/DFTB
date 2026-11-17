package ca.devdcs.backup.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.FormTextField
import ca.devdcs.backup.ui.destinations.SwitchRow
import ca.devdcs.backup.ui.discovery.currentLocale
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** Battery threshold slider bounds and step (percent). */
private const val BATTERY_MIN = 5
private const val BATTERY_MAX = 95
private const val BATTERY_STEP = 5
private const val BATTERY_DEFAULT = 30

/**
 * (e) When the profile runs unattended (frequency, time, days or interval, device conditions,
 * catch-up after a missed slot) and how many backups it keeps (keep-last / max age).
 */
@Composable
internal fun ScheduleStep(state: WizardUiState, viewModel: ProfileWizardViewModel, modifier: Modifier) {
    val draft = state.draft ?: return
    val schedule = draft.schedule
    val wording = rememberScheduleWording()
    val locale = currentLocale()
    val firstDay = firstDayOfWeek(locale)
    val networkDestination = state.selectedDestination?.type?.isNetwork == true
    val problems = state.visibleProblemsFor(WizardStep.SCHEDULE)
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    fun editSchedule(transform: (SchedulePolicy) -> SchedulePolicy) = viewModel.edit { it.copy(schedule = transform(it.schedule)) }

    LazyColumn(modifier = modifier, contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (problems.isNotEmpty()) item(key = "problems") { ProblemLines(problems) }

        item(key = "schedule") {
            SectionCard(title = stringResource(R.string.profiles_sched_title), icon = Icons.Outlined.Schedule) {
                SwitchRow(
                    label = stringResource(R.string.profiles_sched_enable),
                    hint = stringResource(R.string.profiles_sched_enable_hint),
                    checked = draft.scheduleEnabled,
                    onCheckedChange = { checked -> viewModel.edit { it.copy(scheduleEnabled = checked) } },
                )
                if (draft.scheduleEnabled) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ScheduleFrequency.entries.forEachIndexed { index, frequency ->
                            SegmentedButton(
                                selected = schedule.frequency == frequency,
                                onClick = {
                                    editSchedule { policy ->
                                        // Weekly needs at least one day; start from today's weekday rather than an error.
                                        val days = if (frequency == ScheduleFrequency.WEEKLY && policy.daysOfWeek.isEmpty()) {
                                            setOf(LocalDate.now().dayOfWeek.value)
                                        } else {
                                            policy.daysOfWeek
                                        }
                                        policy.copy(frequency = frequency, daysOfWeek = days)
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = ScheduleFrequency.entries.size),
                                label = { Text(stringResource(frequency.labelRes()), maxLines = 1) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    if (schedule.frequency == ScheduleFrequency.INTERVAL) R.string.profiles_sched_anchor else R.string.profiles_sched_time,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                wording.time(schedule.hour.coerceIn(0, 23), schedule.minute.coerceIn(0, 59)),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        OutlinedButton(onClick = { pickingTime = true }) {
                            Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.profiles_sched_change_time), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    when (schedule.frequency) {
                        ScheduleFrequency.WEEKLY -> {
                            Text(stringResource(R.string.profiles_sched_days), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (0 until 7).map { firstDay.plus(it.toLong()) }.forEach { day ->
                                    val selected = day.value in schedule.daysOfWeek
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            editSchedule { policy ->
                                                policy.copy(daysOfWeek = if (selected) policy.daysOfWeek - day.value else policy.daysOfWeek + day.value)
                                            }
                                        },
                                        label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                                        modifier = Modifier.semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale) },
                                    )
                                }
                            }
                        }
                        ScheduleFrequency.INTERVAL -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FormTextField(
                                    value = draft.intervalText,
                                    onValueChange = { text -> viewModel.edit { it.copy(intervalText = text.filter(Char::isDigit).take(MAX_DIGITS)) } },
                                    label = stringResource(R.string.profiles_sched_every),
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f),
                                )
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                                    IntervalUnit.entries.forEachIndexed { index, unit ->
                                        SegmentedButton(
                                            selected = draft.intervalUnit == unit,
                                            onClick = { viewModel.edit { it.copy(intervalUnit = unit) } },
                                            shape = SegmentedButtonDefaults.itemShape(index = index, count = IntervalUnit.entries.size),
                                            label = { Text(stringResource(unit.labelRes()), maxLines = 1) },
                                        )
                                    }
                                }
                            }
                            HintLine(stringResource(R.string.profiles_sched_interval_min, SchedulePolicy.MIN_INTERVAL_MINUTES))
                        }
                        ScheduleFrequency.DAILY -> Unit
                    }
                    draft.effectiveSchedule()?.let { effective ->
                        Text(
                            ScheduleSummary.describe(effective, enabled = true, networkDestination, wording, firstDay),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        if (draft.scheduleEnabled) {
            item(key = "conditions") {
                SectionCard(title = stringResource(R.string.profiles_sched_conditions), icon = Icons.Outlined.BatteryChargingFull) {
                    if (networkDestination) {
                        SwitchRow(
                            label = stringResource(R.string.profiles_sched_unmetered),
                            hint = stringResource(R.string.profiles_sched_unmetered_hint),
                            checked = schedule.requireUnmetered,
                            onCheckedChange = { checked -> editSchedule { it.copy(requireUnmetered = checked) } },
                        )
                    } else {
                        HintLine(stringResource(R.string.profiles_sched_local_no_network))
                    }
                    SwitchRow(
                        label = stringResource(R.string.profiles_sched_charging),
                        checked = schedule.requireCharging,
                        onCheckedChange = { checked -> editSchedule { it.copy(requireCharging = checked) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.profiles_sched_idle),
                        hint = stringResource(R.string.profiles_sched_idle_hint),
                        checked = schedule.requireDeviceIdle,
                        onCheckedChange = { checked -> editSchedule { it.copy(requireDeviceIdle = checked) } },
                    )
                    val battery = schedule.minBatteryPercent
                    SwitchRow(
                        label = stringResource(R.string.profiles_sched_battery),
                        hint = battery?.let { stringResource(R.string.profiles_sched_battery_value, it) },
                        checked = battery != null,
                        onCheckedChange = { checked -> editSchedule { it.copy(minBatteryPercent = if (checked) BATTERY_DEFAULT else null) } },
                    )
                    if (battery != null) {
                        val description = stringResource(R.string.profiles_sched_battery_value, battery)
                        Slider(
                            value = battery.coerceIn(BATTERY_MIN, BATTERY_MAX).toFloat(),
                            onValueChange = { value ->
                                val snapped = ((value / BATTERY_STEP).roundToInt() * BATTERY_STEP).coerceIn(BATTERY_MIN, BATTERY_MAX)
                                editSchedule { it.copy(minBatteryPercent = snapped) }
                            },
                            valueRange = BATTERY_MIN.toFloat()..BATTERY_MAX.toFloat(),
                            steps = (BATTERY_MAX - BATTERY_MIN) / BATTERY_STEP - 1,
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.profiles_sched_catch_up),
                        hint = stringResource(R.string.profiles_sched_catch_up_hint),
                        checked = schedule.runOnBootIfMissed,
                        onCheckedChange = { checked -> editSchedule { it.copy(runOnBootIfMissed = checked) } },
                    )
                }
            }
        }

        item(key = "retention") {
            SectionCard(title = stringResource(R.string.profiles_retention_title), icon = Icons.Outlined.AutoDelete) {
                SwitchRow(
                    label = stringResource(R.string.profiles_retention_keep_last_switch),
                    checked = draft.keepLastEnabled,
                    onCheckedChange = { checked -> viewModel.edit { it.copy(keepLastEnabled = checked) } },
                )
                if (draft.keepLastEnabled) {
                    FormTextField(
                        value = draft.keepLastText,
                        onValueChange = { text -> viewModel.edit { it.copy(keepLastText = text.filter(Char::isDigit).take(MAX_DIGITS)) } },
                        label = stringResource(R.string.profiles_retention_keep_last_field),
                        keyboardType = KeyboardType.Number,
                        error = if (DraftProblem.KEEP_LAST_INVALID in problems) stringResource(DraftProblem.KEEP_LAST_INVALID.messageRes()) else null,
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.profiles_retention_max_age_switch),
                    checked = draft.maxAgeEnabled,
                    onCheckedChange = { checked -> viewModel.edit { it.copy(maxAgeEnabled = checked) } },
                )
                if (draft.maxAgeEnabled) {
                    FormTextField(
                        value = draft.maxAgeText,
                        onValueChange = { text -> viewModel.edit { it.copy(maxAgeText = text.filter(Char::isDigit).take(MAX_DIGITS)) } },
                        label = stringResource(R.string.profiles_retention_max_age_field),
                        keyboardType = KeyboardType.Number,
                        error = if (DraftProblem.MAX_AGE_INVALID in problems) stringResource(DraftProblem.MAX_AGE_INVALID.messageRes()) else null,
                    )
                }
                Text(
                    ScheduleSummary.retention(draft.effectiveRetention(), wording),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                HintLine(stringResource(R.string.profiles_retention_note))
            }
        }
    }

    if (pickingTime) {
        TimePickerDialog(
            hour = schedule.hour.coerceIn(0, 23),
            minute = schedule.minute.coerceIn(0, 59),
            onConfirm = { hour, minute ->
                editSchedule { it.copy(hour = hour, minute = minute) }
                pickingTime = false
            },
            onDismiss = { pickingTime = false },
        )
    }
}

/** Longest number the interval / retention fields accept (keeps parsing in Int range). */
private const val MAX_DIGITS = 5

@Composable
private fun TimePickerDialog(hour: Int, minute: Int, onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pickerState = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_sched_pick_time)) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) { Text(stringResource(R.string.profiles_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_cancel)) } },
    )
}
