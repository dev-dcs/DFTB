package ca.devdcs.backup.data.security

import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The JSON dialect sealed inside vault blobs. Deliberately its own [Json] instance rather than the
 * app-wide one from `AppModule`: the on-disk format must stay stable even if the app's general
 * JSON settings (discriminator name, defaults, leniency) change later.
 */
internal object SecretJson {

    private val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = false
        prettyPrint = false
    }

    fun encodeToBytes(secret: Secret): ByteArray =
        json.encodeToString(Secret.serializer(), secret).toByteArray(Charsets.UTF_8)

    /**
     * Caller owns [bytes] and should wipe them afterwards; the returned [Secret] is the only surviving copy.
     *
     * On failure the thrown [VaultException] deliberately omits the kotlinx exception as its cause: with
     * `exceptionsWithDebugInfo` (on by default) a [kotlinx.serialization.json.JsonDecodingException] message
     * embeds a minified fragment of the decoded plaintext JSON, which here is decrypted secret material, so
     * propagating it as the cause would leak secret bytes into any log that records the cause chain.
     */
    fun decodeFromBytes(bytes: ByteArray): Secret = try {
        json.decodeFromString(Secret.serializer(), String(bytes, Charsets.UTF_8))
    } catch (e: SerializationException) {
        throw VaultException("Vault entry decrypted but its JSON is not a known Secret", reason = VaultException.Reason.CORRUPT_BLOB)
    } catch (e: IllegalArgumentException) {
        throw VaultException("Vault entry decrypted but its JSON is malformed", reason = VaultException.Reason.CORRUPT_BLOB)
    }
}
