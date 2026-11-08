package ca.devdcs.backup

import ca.devdcs.backup.core.schedule.BackupScheduler
import ca.devdcs.backup.companion.CompanionJournalMirror
import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.logging.LogBuffer
import ca.devdcs.backup.core.notification.NotificationChannels
import ca.devdcs.backup.core.shell.ShellInitializer
import ca.devdcs.backup.core.startup.OnboardingReconciler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Process entry point.
 *
 * Responsibilities are deliberately few: install the log sink, seed the runtime locale from the
 * persisted preference before any UI exists, register notification channels, and hand WorkManager
 * a Hilt-aware worker factory. Nothing here touches root, Shizuku or storage; those engines are
 * created lazily by the screens and workers that need them.
 */
@HiltAndroidApp
class DftbApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var localeManager: LocaleManager
    @Inject lateinit var notificationChannels: NotificationChannels
    @Inject lateinit var logBuffer: LogBuffer
    @Inject lateinit var onboardingReconciler: OnboardingReconciler
    @Inject lateinit var companionJournalMirror: CompanionJournalMirror
    @Inject lateinit var backupScheduler: BackupScheduler

    override fun onCreate() {
        super.onCreate()
        DftbLog.install(sink = logBuffer, verbose = BuildConfig.DEBUG)
        DftbLog.i(TAG, "DFTB ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) starting")

        // Install libsu's default shell builder (mount-master, stderr redirect, 20 s timeout)
        // before any probe or engine can call Shell.getShell(); the builder is immutable once used.
        ShellInitializer.ensureInitialized()

        // Must run before the first Activity so the very first frame is already in the user's
        // language. Reads one tiny DataStore file synchronously, once.
        localeManager.initialize()

        notificationChannels.ensureCreated()
        notificationChannels.observeLanguageChanges()

        // Keep WorkManager schedules in step with backup profiles (idempotent; resolves WorkManager lazily).
        backupScheduler.start()

        // Reclaim destinations a previous, abandoned onboarding run committed to Room (and release
        // their held SAF URI grants) before the wizard can be shown again. No-op once onboarding is done.
        onboardingReconciler.reconcileOrphansIfIncomplete()

        // In companion-module mode, keep the module's root journal in step with Room so its
        // late-boot service.sh can see interrupted jobs. Inert in every other mode.
        companionJournalMirror.start()
    }

    /**
     * WorkManager configuration. The default `androidx.startup` initializer is removed in the
     * manifest, so this is the only place WorkManager is configured; without it, Hilt-injected
     * workers could not be instantiated.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    private companion object {
        const val TAG = "App"
    }
}
