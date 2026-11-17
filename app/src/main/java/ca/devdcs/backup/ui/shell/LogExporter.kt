package ca.devdcs.backup.ui.shell

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the "share log" intent of the job monitor. The log is written to a cache file and shared
 * through the app's FileProvider (authority `<applicationId>.files`, `cache-path` root), so a
 * long log is not squeezed through an intent extra; if the file cannot be written the text itself
 * is sent, trimmed to what fits in a binder transaction.
 *
 * Old exports are pruned on every write so the cache never accumulates logs.
 */
@Singleton
class LogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * @param fullText the complete log text for the file.
     * @param fallbackText a bounded copy for the EXTRA_TEXT fallback.
     */
    suspend fun shareIntent(fileName: String, subject: String, fullText: String, fallbackText: String): Intent {
        val uri = withContext(ioDispatcher) { writeShareFile(fileName, fullText) }
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME_TEXT)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
        if (uri != null) {
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri(subject, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            send.putExtra(Intent.EXTRA_TEXT, fallbackText)
        }
        return send
    }

    private fun writeShareFile(fileName: String, text: String): Uri? = try {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        val safeName = fileName.replace(UNSAFE_CHARS, "_").take(MAX_NAME_LENGTH)
        val file = File(dir, safeName)
        file.writeText(text, Charsets.UTF_8)
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        DftbLog.w(TAG, "Could not write the log export", e)
        null
    } catch (e: IllegalArgumentException) {
        // FileProvider rejects a path outside its configured roots.
        DftbLog.w(TAG, "Log export path not shareable", e)
        null
    } catch (e: SecurityException) {
        DftbLog.w(TAG, "Log export not permitted", e)
        null
    }

    private companion object {
        const val TAG = "LogExport"
        const val SHARE_DIR = "shared_logs"
        const val AUTHORITY_SUFFIX = ".files"
        const val MIME_TEXT = "text/plain"
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        const val MAX_NAME_LENGTH = 96
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
