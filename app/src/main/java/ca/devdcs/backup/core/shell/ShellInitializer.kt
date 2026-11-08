package ca.devdcs.backup.core.shell

import ca.devdcs.backup.BuildConfig
import ca.devdcs.backup.core.logging.DftbLog
import com.topjohnwu.superuser.Shell
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time libsu configuration. libsu reads its default builder the first time `Shell.getShell()`
 * runs and ignores later changes, so the flags (mount-master namespace, stderr redirected into
 * `out`) and the 20 s superuser-prompt timeout must be set before any engine or probe opens a
 * shell. The Application calls this in `onCreate`; [ca.devdcs.backup.core.environment.EnvironmentProbe]
 * calls it again defensively, which is a no-op thanks to the [AtomicBoolean] guard.
 */
object ShellInitializer {

    private const val TAG = "ShellInit"
    /** Seconds libsu waits for the `su` process to answer before giving up on a root shell. */
    private const val SHELL_TIMEOUT_SECONDS = 20L

    private val initialized = AtomicBoolean(false)

    val isInitialized: Boolean get() = initialized.get()

    fun ensureInitialized() {
        if (!initialized.compareAndSet(false, true)) return
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(SHELL_TIMEOUT_SECONDS),
        )
        DftbLog.d(TAG, "libsu default builder installed (mount-master, stderr redirected, ${SHELL_TIMEOUT_SECONDS}s timeout)")
    }
}
