package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.storage.StorageException

/**
 * Where an rclone session points: the remote prefix (`name:` or an on-the-fly connection string
 * such as `:s3,provider=AWS:`), the base path below it, and the environment that carries any
 * secrets for an on-the-fly remote.
 *
 * Exists so remote-spec construction (quoting, base-path joining, secrets-in-env) is pure and
 * unit-tested. [environment] holds credentials and must never be logged or put on argv; use
 * [toString] (redacted) for diagnostics.
 */
class RcloneTarget(
    /** `name:` or `:backend,opt=val:` — never contains a secret. */
    val remotePrefix: String,
    /** Normalized base path below the remote (bucket/folder), `""` for the remote root. */
    val basePath: String,
    /** `RCLONE_<BACKEND>_<OPTION>` entries; may contain secrets. */
    val environment: Map<String, String>,
    /** Loggable form of [remotePrefix]; on-the-fly parameter values are left out. */
    private val displayPrefix: String = remotePrefix,
    /** rclone backend type (`s3`, `b2`, ...) when known; drives backend-specific upload tuning. */
    val backendType: String? = null,
) {
    /** Extra flags for `rcat` so huge streamed uploads fit the backend's part limits. */
    val uploadFlags: List<String> get() = uploadFlagsFor(backendType)

    /** The rclone argument addressing [path] (relative to [basePath]). */
    fun spec(path: String): String = remotePrefix + RclonePaths.join(basePath, path)

    override fun toString(): String = "RcloneTarget(remote=$displayPrefix, base=$basePath, env=${environment.keys})"

    companion object {
        private val PARAM_KEY = Regex("^[a-z0-9_]+$")

        /**
         * Streamed uploads have unknown size, so rclone uses the default chunk size: S3 caps at
         * 10,000 parts (5 MiB → ~48 GiB). 64 MiB chunks raise that to ~625 GiB; concurrency 2
         * bounds memory (chunk × concurrency ≈ 128 MiB). B2 likewise (96 MiB × 10,000 parts).
         */
        fun uploadFlagsFor(backendType: String?): List<String> = when (backendType) {
            "s3" -> listOf("--s3-chunk-size", "64M", "--s3-upload-concurrency", "2")
            "b2" -> listOf("--b2-chunk-size", "96M", "--b2-upload-concurrency", "2")
            else -> emptyList()
        }

        /** A remote defined in rclone.conf; [type] is its `type` option. */
        fun named(remoteName: String, basePath: String?, type: String? = null): RcloneTarget {
            if (!RcloneIni.isValidRemoteName(remoteName)) {
                throw StorageException(StorageException.Reason.MISCONFIGURED, "Invalid rclone remote name '$remoteName'")
            }
            return RcloneTarget("$remoteName:", RclonePaths.normalize(basePath), emptyMap(), backendType = type)
        }

        /**
         * An on-the-fly remote: `:backend,k=v,...:` built from non-secret [params]; [secrets] become
         * `RCLONE_<BACKEND>_<KEY>` environment variables so they never appear on a command line.
         */
        fun onTheFly(
            backend: String,
            params: Map<String, String>,
            secrets: Map<String, String>,
            basePath: String?,
        ): RcloneTarget {
            require(PARAM_KEY.matches(backend)) { "Invalid backend name '$backend'" }
            val prefix = buildString {
                append(':').append(backend)
                params.forEach { (k, v) ->
                    require(PARAM_KEY.matches(k)) { "Invalid connection-string key '$k'" }
                    require(!RcloneIni.isSecretKey(k)) { "Secret option '$k' must be passed as a secret, not on the command line" }
                    append(',').append(k).append('=').append(quoteParam(v))
                }
                append(':')
            }
            val env = secrets.entries.associate { (k, v) ->
                require(PARAM_KEY.matches(k)) { "Invalid option key '$k'" }
                envName(backend, k) to v
            }
            val display = ":$backend" + (if (params.isEmpty()) "" else params.keys.joinToString(",", prefix = ",") { "$it=…" }) + ":"
            return RcloneTarget(prefix, RclonePaths.normalize(basePath), env, display, backend)
        }

        /** `RCLONE_S3_ACCESS_KEY_ID` for (`s3`, `access_key_id`). */
        fun envName(backend: String, option: String): String = "RCLONE_${backend.uppercase()}_${option.uppercase()}"

        /**
         * rclone connection-string quoting: values containing `,` `:` `=` quotes or whitespace are
         * wrapped in double quotes with embedded double quotes doubled.
         */
        fun quoteParam(value: String): String =
            if (value.isEmpty() || value.any { it == ',' || it == ':' || it == '=' || it == '"' || it == '\'' || it.isWhitespace() }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
    }
}
