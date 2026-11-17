package ca.devdcs.backup

import android.content.Intent
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.schedule.ScheduleNotifier
import ca.devdcs.backup.core.usb.UsbAttachEvents
import ca.devdcs.backup.ui.DftbApp
import ca.devdcs.backup.ui.shell.DeepLinkInbox
import ca.devdcs.backup.ui.shell.ShellIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The single Activity. Everything on screen is Compose.
 *
 * It extends [AppCompatActivity] (not ComponentActivity) because the per-app language feature on
 * Android 10–12 is implemented by AppCompat, and the manifest declares `locale|layoutDirection` in
 * configChanges so a language switch never recreates this Activity: AppCompat sees that the
 * Activity handles the change itself, updates the resources in place and calls
 * [onConfigurationChanged], while Compose swaps strings through [ca.devdcs.backup.core.locale.ProvideAppLocale].
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var localeManager: LocaleManager
    @Inject lateinit var usbAttachEvents: UsbAttachEvents
    @Inject lateinit var deepLinkInbox: DeepLinkInbox

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Make the framework/AppCompat locale list agree with the persisted DataStore choice. Done
        // here rather than in Application.onCreate because AppCompat requires an Activity context
        // to exist before application locales are set on API < 33.
        localeManager.syncFrameworkLocales()

        handleUsbIntent(intent)
        // A recreated Activity (savedInstanceState != null) already handled its launch intent.
        if (savedInstanceState == null) handleDeepLink(intent)

        setContent {
            DftbApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
        handleDeepLink(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DftbLog.d(TAG, "Configuration changed in place: locales=${newConfig.locales.toLanguageTags()} uiMode=${newConfig.uiMode}")
    }

    /**
     * USB OTG mass-storage attach. The device is handed to [UsbAttachEvents]; the storage engine
     * mounts and inspects it, and any visible screen may surface a prompt.
     */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            ?: return
        DftbLog.i(TAG, "USB device attached: vid=${device.vendorId} pid=${device.productId} name=${device.deviceName}")
        usbAttachEvents.onAttached(device)
    }

    /**
     * Notification taps (profile alerts, job progress) are queued in [DeepLinkInbox]; the shell
     * navigates once its graph exists and onboarding is done.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return
        val link = ShellIntents.parse(
            action = intent.action,
            profileId = intent.getStringExtra(ScheduleNotifier.EXTRA_PROFILE_ID),
            jobId = intent.getStringExtra(ShellIntents.EXTRA_JOB_ID),
            launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
        ) ?: return
        DftbLog.d(TAG, "Deep link: $link")
        deepLinkInbox.post(link)
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
