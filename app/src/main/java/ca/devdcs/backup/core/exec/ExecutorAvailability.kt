package ca.devdcs.backup.core.exec

/**
 * Privilege ordering for executors, kept in one place so the router and any future
 * auto-detection logic agree on what "highest privilege" means: ROOT > SHIZUKU > NONE.
 *
 * It exists separately from the [ElevationTier] enum's declaration order so the ranking the app
 * reasons about is explicit and testable, and does not silently change if the enum constants are
 * ever reordered for an unrelated reason (storage keys, UI listing, etc.).
 */
object ExecutorAvailability {

    /** Higher = more privileged. Used to pick the strongest tier available at runtime. */
    fun privilege(tier: ElevationTier): Int = when (tier) {
        ElevationTier.NONE -> 0
        ElevationTier.SHIZUKU -> 1
        ElevationTier.ROOT -> 2
    }

    /** Orders executors strongest-first (ROOT, then SHIZUKU, then NONE). */
    val byPrivilegeDescending: Comparator<CommandExecutor> =
        compareByDescending { executor -> privilege(executor.tier) }
}
