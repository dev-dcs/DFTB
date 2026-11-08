package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.core.logging.DftbLog
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Installs the full BouncyCastle JCE provider in place of Android's stripped, repackaged "BC".
 *
 * Exists because Android registers a cut-down provider under the name "BC" (no X25519, no
 * ChaCha20-Poly1305, few EC curves): sshj looks providers up by that name, so modern key exchange
 * and Ed25519/ECDSA keys fail unless the bundled bcprov replaces it before the first SSH connect.
 * The replacement keeps the earlier build priority slot so unrelated JCA lookups are not reordered.
 * Idempotent and thread-safe; call it before any sshj use.
 */
object SecurityProviders {

    @Volatile
    private var installed = false

    fun ensureBouncyCastle() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val name = BouncyCastleProvider.PROVIDER_NAME
            val current = Security.getProvider(name)
            if (current == null || current.javaClass != BouncyCastleProvider::class.java) {
                val slot = Security.getProviders().indexOfFirst { it.name == name } // 0-based, -1 if absent
                if (current != null) Security.removeProvider(name)
                val provider = BouncyCastleProvider()
                val position = if (slot >= 0) Security.insertProviderAt(provider, slot + 1) else Security.addProvider(provider)
                DftbLog.i(TAG, "Installed full BouncyCastle provider at position $position")
            }
            SecurityUtils.setSecurityProvider(name)
            installed = true
        }
    }

    private const val TAG = "SecurityProviders"
}
