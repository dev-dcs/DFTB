package ca.devdcs.backup.core.startup

import android.content.Context
import android.content.Intent
import android.net.Uri
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.repository.DestinationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cleans up destinations the first-launch wizard committed to Room before the user finished
 * onboarding. The wizard saves a chosen destination (and takes its persistable SAF URI grant)
 * immediately on the BASELINE step, so a force-quit before Finish — which never invokes the
 * ViewModel's `onCleared` — leaves an orphan row and a held URI permission behind. The wizard's own
 * swap/discard logic cannot see those across a process death, so the sweep happens here at launch.
 *
 * Safe because the wizard is the only code path that can create a destination before onboarding
 * completes: while [AppPreferences.onboardingCompleted] is false, every destination row is a
 * wizard orphan and is deleted, and its SAF grant released. Once onboarding is complete the rows
 * are real user data and this does nothing. A snapshot is taken up front, so a destination the
 * user re-creates in a concurrent wizard session (a fresh id) is never caught by the sweep.
 */
@Singleton
class OnboardingReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferencesRepository,
    private val destinationRepository: DestinationRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Launches the sweep on the application scope; returns immediately so startup is not blocked. */
    fun reconcileOrphansIfIncomplete() {
        appScope.launch(ioDispatcher) {
            runCatching { reconcile() }
                .onFailure { error -> DftbLog.w(TAG, "Onboarding orphan reconciliation failed", error) }
        }
    }

    private suspend fun reconcile() {
        if (preferences.preferences.first().onboardingCompleted) return
        val orphans = destinationRepository.getAll()
        if (orphans.isEmpty()) return
        DftbLog.i(TAG, "Onboarding incomplete at launch; reclaiming ${orphans.size} wizard-created destination(s)")
        for (destination in orphans) {
            releaseTreeUriPermission(destination.config.treeUri)
            destinationRepository.delete(destination.id)
        }
    }

    private fun releaseTreeUriPermission(treeUri: String?) {
        val uri = treeUri?.let(Uri::parse) ?: return
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (error: SecurityException) {
            DftbLog.d(TAG, "Persistable permission for $uri already released")
        }
    }

    private companion object {
        const val TAG = "OnboardingReconciler"
    }
}
