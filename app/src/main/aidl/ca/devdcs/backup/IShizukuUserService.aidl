package ca.devdcs.backup;

import ca.devdcs.backup.IShellCallback;

/**
 * The remote interface the app talks to inside the Shizuku user-service process. That process runs
 * with adb (uid 2000) or root (uid 0) privileges and loads {@code ShizukuUserServiceImpl} from this
 * APK's dex; it has no Activity/UI and exists only to spawn child processes on the app's behalf.
 *
 * DESIGN: there is ONE execution primitive, {@link #execute}, used for both the buffered and the
 * streaming paths on the app side. It launches the child, wires its stdout/stderr to an
 * {@link IShellCallback}, and returns IMMEDIATELY with an integer token (so a long-running command
 * never ties up a binder thread for its whole lifetime). The caller holds the token to {@link
 * #cancel} the child on coroutine cancellation; {@link IShellCallback#onExit} reports completion.
 * No custom Parcelable is used — only primitives, String[]/byte[] and the callback — so the module
 * needs neither kotlin-parcelize nor a hand-written Parcelable, keeping the cross-process contract
 * minimal and robust.
 */
interface IShizukuUserService {
    /** Returned by {@link #execute} when the child could not be started at all (bad argv, bad cwd). */
    const int LAUNCH_FAILED = -1;

    /**
     * Reported via {@link IShellCallback#onExit} when the service force-killed the child for
     * exceeding its timeout. Deliberately equal to {@code CommandResult.EXIT_TIMEOUT} so the app can
     * pass the code straight through without translation.
     */
    const int EXIT_TIMEOUT = -2;

    /** The uid the service process runs as: 2000 (adb/shell) or 0 (root). For diagnostics. */
    int getProcessUid() = 1;

    /**
     * Launch a child process and stream its output to [callback]. Returns a positive token to pass
     * to {@link #cancel}, or {@link #LAUNCH_FAILED} if the process could not be started (in which
     * case [callback] is never invoked). [environment] entries are {@code KEY=VALUE} strings layered
     * onto the service process environment. [workingDirectory] may be null to inherit the service's.
     * [stdin] may be null; when non-null it is written to the child's stdin which is then closed.
     * [timeoutMillis] &le; 0 means no timeout.
     */
    int execute(in String[] argv, in String[] environment, String workingDirectory,
                in byte[] stdin, boolean redirectErrorStream, long timeoutMillis,
                IShellCallback callback) = 2;

    /** Force-kill the child identified by [token] (no-op if it already exited). Fire-and-forget. */
    oneway void cancel(int token) = 3;

    /**
     * Shizuku-reserved teardown hook. The Shizuku server invokes this (at the fixed transaction code
     * 16777115) when the service is removed because it was bound with {@code daemon(false)}; the
     * implementation kills any surviving children and calls {@code System.exit} so the privileged
     * process does not linger. The id 16777114 yields that reserved transaction code
     * (FIRST_CALL_TRANSACTION + id), matching {@code ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy}.
     */
    void destroy() = 16777114;
}
