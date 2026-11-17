package ca.devdcs.backup.data.db.converters

import kotlinx.serialization.json.Json

/**
 * The one Json instance used for every JSON column. Deliberately separate from the injected
 * app-wide Json: column encoding is a storage contract that must stay stable even if the UI or
 * network layers later tune their own settings. `encodeDefaults` keeps every field explicit on
 * disk; `ignoreUnknownKeys` + `coerceInputValues` let an older build read rows written by a newer
 * one.
 */
internal object DbJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        explicitNulls = true
        prettyPrint = false
        isLenient = false
    }
}
