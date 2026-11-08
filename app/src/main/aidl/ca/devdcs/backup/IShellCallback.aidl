package ca.devdcs.backup;

/**
 * Streaming sink for one command running in the Shizuku user-service process.
 *
 * WHY: a backup/restore `tar`/`dd`/`rclone` runs for minutes to hours and must report progress live,
 * so the user service pushes each output line to the app as it is produced instead of buffering the
 * whole transcript. The callbacks are deliberately NOT `oneway`: a two-way (blocking) binder call
 * gives natural backpressure. The service's reader thread blocks inside {@link #onStdout} until the
 * app has accepted the line, which lets the OS pipe buffer fill and throttle the child process,
 * rather than flooding the 1 MB async transaction buffer and crashing with a TransactionTooLarge or
 * DeadObject fault when the collector is slower than the producer.
 *
 * Ordering guarantee relied on by the app: the service joins its reader threads before invoking
 * {@link #onExit}, and because stdout/stderr delivery is synchronous, every {@link #onStdout} /
 * {@link #onStderr} transaction has completed on the app side before {@link #onExit} is dispatched.
 */
interface IShellCallback {
    /** One line of standard output (newline stripped). */
    void onStdout(String line);

    /** One line of standard error (newline stripped). Never called when stderr was redirected into stdout. */
    void onStderr(String line);

    /**
     * Terminal event: the process has exited and all output has already been delivered. [exitCode]
     * is the real process exit status, or {@link IShizukuUserService#EXIT_TIMEOUT} if the service
     * killed it for exceeding its timeout.
     */
    void onExit(int exitCode);
}
