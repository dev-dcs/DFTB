package ca.devdcs.backup.ui.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.ui.onboarding.BulletText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/*
 * Building blocks shared by every settings section. Material 3 only, colors from MaterialTheme,
 * start/end paddings only so every row mirrors correctly in Arabic.
 */

/** Widest a single settings column grows on tablets before it starts to read badly. */
internal val SettingsContentMaxWidth = 720.dp

/** One format argument of a [SettingsMessage]: raw text, or a string resolved at display time. */
sealed interface MessageArg {
    data class Text(val value: String) : MessageArg
    data class Res(@StringRes val resId: Int) : MessageArg
}

/**
 * A one-shot, localizable message a settings ViewModel wants shown in a snackbar. Holds a resource
 * id plus arguments (raw values or nested resource ids) so the ViewModel never needs a localized
 * Context: everything is resolved by the screen in the language currently on display.
 */
data class SettingsMessage(
    val messageRes: Int,
    val args: List<MessageArg> = emptyList(),
    /** When set, [messageRes] is a plurals resource resolved for this quantity. */
    val quantity: Int? = null,
) {
    fun resolve(context: Context): String {
        val resolved = args.map { arg ->
            when (arg) {
                is MessageArg.Text -> arg.value
                is MessageArg.Res -> context.getString(arg.resId)
            }
        }.toTypedArray()
        return when {
            quantity != null -> context.resources.getQuantityString(messageRes, quantity, *resolved)
            resolved.isEmpty() -> context.getString(messageRes)
            else -> context.getString(messageRes, *resolved)
        }
    }

    companion object {
        fun text(@StringRes messageRes: Int, vararg values: String) =
            SettingsMessage(messageRes, values.map { MessageArg.Text(it) })

        /** A plurals message whose only argument is [count] itself. */
        fun count(@PluralsRes pluralsRes: Int, count: Int) =
            SettingsMessage(pluralsRes, listOf(MessageArg.Text(count.toString())), quantity = count)
    }
}

/**
 * Shows every [SettingsMessage] from [messages] in [snackbarHostState]. Each snackbar runs in its
 * own child coroutine so a slow dismissal never stalls the messages queued behind it.
 */
@Composable
internal fun CollectSettingsMessages(messages: Flow<SettingsMessage>, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(messages, context) {
        messages.collect { message ->
            scope.launch { snackbarHostState.showSnackbar(message.resolve(context)) }
        }
    }
}

/**
 * The scrolling body of one settings section: a lazy column capped at [SettingsContentMaxWidth]
 * and centred, so phones get a full-width list and tablets a readable column.
 */
@Composable
internal fun SettingsList(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = SettingsContentMaxWidth)
                .fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Uppercase-free, primary-tinted heading above a group of rows (Material 3 list subheader). */
@Composable
internal fun SettingsCategoryHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A line with a leading status icon; used for "granted", "missing", "running" style facts. */
@Composable
internal fun StatusLine(icon: ImageVector, tint: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Card tinted with the error container: blocking problems and irreversible-action warnings. */
@Composable
internal fun WarningCard(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    content: @Composable () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

/** Card tinted with the tertiary container: informational notes that do not block anything. */
@Composable
internal fun NoteCard(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            if (icon != null) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** Label + hint on the start side, switch on the end side; the whole row toggles. */
@Composable
internal fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
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

/**
 * Confirmation for an action with consequences. [consequences] are rendered as bullets so the
 * user reads exactly what will happen; when [acknowledgement] is set the confirm button stays
 * disabled until its checkbox is ticked (used for irreversible deletions).
 */
@Composable
internal fun ConsequenceDialog(
    title: String,
    consequences: List<String>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    destructive: Boolean = false,
    acknowledgement: String? = null,
) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val confirmEnabled = acknowledgement == null || acknowledged
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
                consequences.forEach { BulletText(it) }
                if (acknowledgement != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = { acknowledged = it })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = acknowledged, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(acknowledgement, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/** Localized absolute date and time (the context carries the app language). */
internal fun formatDateTime(context: Context, epochMillis: Long): String =
    DateUtils.formatDateTime(
        context,
        epochMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )

/** Default content padding of a section body. */
internal val SectionContentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
