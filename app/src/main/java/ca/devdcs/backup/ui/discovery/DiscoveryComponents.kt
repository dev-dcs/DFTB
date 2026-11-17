package ca.devdcs.backup.ui.discovery

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Small presentational pieces shared by the three Discovery tabs. Colors come only from
 * MaterialTheme; layout uses start/end so it mirrors under RTL.
 */

/** Emphasis of a [StatusBadge] / [NoticeCard], mapped to Material container roles. */
enum class Tone { NEUTRAL, INFO, WARNING, ERROR }

private data class ToneColors(val container: Color, val content: Color)

@Composable
@ReadOnlyComposable
private fun toneColors(tone: Tone): ToneColors {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        Tone.NEUTRAL -> ToneColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
        Tone.INFO -> ToneColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        Tone.WARNING -> ToneColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        Tone.ERROR -> ToneColors(scheme.errorContainer, scheme.onErrorContainer)
    }
}

/** A compact pill label ("Logical", "Critical", "Split APK"). */
@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val colors = toneColors(tone)
    Surface(color = colors.container, contentColor = colors.content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** A tinted card with an icon, a title, an optional body and optional actions underneath. */
@Composable
fun NoticeCard(
    title: String,
    tone: Tone,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    body: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = toneColors(tone)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null)
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
            if (actions != null) actions()
        }
    }
}

/** Short localized size ("1.2 GB"), SI units like the rest of Android. */
fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** The locale the UI is currently rendered in (follows the in-app language switch). */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** Localized medium date + short time. */
fun formatDateTime(millis: Long, locale: Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(millis))

/** Localized medium date of a DatePicker value (UTC midnight), formatted in UTC so the day never shifts. */
fun formatPickerDay(dayUtcMillis: Long, locale: Locale): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(dayUtcMillis))

/** Composable shortcut for [formatSize]. */
@Composable
@ReadOnlyComposable
fun sizeText(bytes: Long): String = formatSize(LocalContext.current, bytes)
