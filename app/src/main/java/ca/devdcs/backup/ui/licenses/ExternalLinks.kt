package ca.devdcs.backup.ui.licenses

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import ca.devdcs.backup.core.logging.DftbLog

/**
 * Safe launching of external Activities from Compose.
 *
 * Exists because `LocalContext.current` under `ProvideAppLocale` is a configuration context, not
 * the Activity: starting an Activity from it without `FLAG_ACTIVITY_NEW_TASK` throws, and a device
 * without a browser (or a locked-down work profile) throws [ActivityNotFoundException]. Both cases
 * are turned into a `false` return so the caller can show a snackbar instead of crashing.
 */
object ExternalLinks {

    private const val TAG = "ExternalLinks"

    /** Opens [url] in the user's browser. Returns false when no app can handle it. */
    fun openUrl(context: Context, url: String): Boolean =
        launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /**
     * Starts [intent] from [context]; adds `FLAG_ACTIVITY_NEW_TASK` when [context] is not an
     * Activity so the framework accepts the call. Returns false if nothing can handle the intent or
     * the system refused to start it.
     */
    fun launch(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            DftbLog.w(TAG, "No activity for ${intent.action} ${intent.data ?: ""}")
            false
        } catch (error: SecurityException) {
            DftbLog.w(TAG, "Not allowed to start ${intent.action}", error)
            false
        } catch (error: IllegalStateException) {
            DftbLog.w(TAG, "Could not start ${intent.action}", error)
            false
        }
    }
}
