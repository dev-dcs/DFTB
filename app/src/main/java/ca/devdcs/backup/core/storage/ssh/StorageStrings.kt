package ca.devdcs.backup.core.storage.ssh

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the localized messages the SFTP/SSH, SMB and NFS backends put into
 * [ca.devdcs.backup.core.storage.StorageException]s. Exists so sessions (which have no UI context)
 * still surface translatable text, while the pure parsers stay free of Android types.
 */
@Singleton
class StorageStrings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun get(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
}
