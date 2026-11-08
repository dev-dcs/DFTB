package ca.devdcs.backup.core.schedule

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process guard so one profile never runs twice at once (its periodic work, a "Back up now"
 * and a boot resume are separate WorkManager items and could otherwise overlap and race on the
 * same checkpoint and job folder). Non-blocking: a second run simply skips.
 */
@Singleton
class ProfileRunLock @Inject constructor() {
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** True when the caller now owns [profileId]; it must call [release] afterwards. */
    fun tryAcquire(profileId: String): Boolean = running.add(profileId)

    fun release(profileId: String) {
        running.remove(profileId)
    }

    fun isRunning(profileId: String): Boolean = profileId in running
}
