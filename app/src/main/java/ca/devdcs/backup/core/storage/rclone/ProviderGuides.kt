package ca.devdcs.backup.core.storage.rclone

import androidx.annotation.StringRes
import ca.devdcs.backup.R

/** How a guide field is entered and validated. */
enum class GuideFieldKind {
    TEXT,
    URL,
    EMAIL,
    NUMBER,
    PASSWORD,
    CHOICE,
    /** JSON from `rclone authorize`, checked by [OAuthTokenValidator]. */
    OAUTH_TOKEN,
    /** Multi-line secret (PEM key); stored with line breaks escaped as `\n`, as rclone expects. */
    MULTILINE_SECRET,
}

/** One input of a [ProviderGuide], mapped 1:1 to an rclone.conf option [key]. */
data class GuideField(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int?,
    val kind: GuideFieldKind,
    val secret: Boolean = false,
    val required: Boolean = true,
    /** rclone needs this value obscured (`rclone obscure`) in the config file. */
    val obscure: Boolean = false,
    val defaultValue: String? = null,
    val choices: List<String> = emptyList(),
)

/**
 * An interactive setup recipe for one provider: what to do outside DFTB ([stepsRes]), which
 * values to collect ([fields]), and which fixed options the resulting remote carries.
 *
 * Exists so the setup UI is data-driven and [RcloneConfigBuilder] can turn the collected values
 * into a valid rclone.conf section without per-provider code. Step strings may contain `%1$s`,
 * which the UI formats with [rcloneType] (used by the shared OAuth steps).
 */
data class ProviderGuide(
    val id: String,
    val rcloneType: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val stepsRes: List<Int>,
    val fields: List<GuideField>,
    /** True when the user must run [authorizeCommand] on a desktop and paste the token JSON. */
    val oauth: Boolean,
    /** Options written verbatim (e.g. `provider = AWS`). */
    val fixedOptions: Map<String, String> = emptyMap(),
    /** Groups of field keys of which at least one must be filled (e.g. SFTP password or key). */
    val atLeastOneOf: List<Set<String>> = emptyList(),
    /** Default remote name offered in the UI. */
    val suggestedRemoteName: String,
) {
    /** The desktop command for OAuth providers, e.g. `rclone authorize "drive"`. */
    val authorizeCommand: String? get() = if (oauth) "rclone authorize \"$rcloneType\"" else null

    fun field(key: String): GuideField? = fields.firstOrNull { it.key == key }
}

/** The catalogue of guided rclone setups. */
object ProviderGuides {

    private val OAUTH_STEPS = listOf(
        R.string.rclone_guide_oauth_step_install,
        R.string.rclone_guide_oauth_step_authorize,
        R.string.rclone_guide_oauth_step_paste,
    )

    private fun accessKeyId() = GuideField("access_key_id", R.string.rclone_field_access_key_id, R.string.rclone_hint_access_key_id, GuideFieldKind.TEXT)
    private fun secretAccessKey() = GuideField("secret_access_key", R.string.rclone_field_secret_access_key, R.string.rclone_hint_secret_access_key, GuideFieldKind.PASSWORD, secret = true)
    private fun token() = GuideField("token", R.string.rclone_field_token, R.string.rclone_hint_token, GuideFieldKind.OAUTH_TOKEN, secret = true)
    private fun clientId() = GuideField("client_id", R.string.rclone_field_client_id, R.string.rclone_hint_client_id, GuideFieldKind.TEXT, required = false)
    private fun clientSecret() = GuideField("client_secret", R.string.rclone_field_client_secret, R.string.rclone_hint_client_id, GuideFieldKind.PASSWORD, secret = true, required = false)

    val S3_AWS = ProviderGuide(
        id = "s3_aws",
        rcloneType = "s3",
        titleRes = R.string.rclone_guide_s3_aws_title,
        summaryRes = R.string.rclone_guide_s3_aws_summary,
        stepsRes = listOf(R.string.rclone_guide_s3_aws_step1, R.string.rclone_guide_s3_aws_step2, R.string.rclone_guide_s3_step_bucket),
        fields = listOf(
            accessKeyId(),
            secretAccessKey(),
            GuideField("region", R.string.rclone_field_region, R.string.rclone_hint_region_aws, GuideFieldKind.TEXT, defaultValue = "us-east-1"),
        ),
        oauth = false,
        fixedOptions = mapOf("provider" to "AWS", "env_auth" to "false"),
        suggestedRemoteName = "aws",
    )

    val S3_MINIO = ProviderGuide(
        id = "s3_minio",
        rcloneType = "s3",
        titleRes = R.string.rclone_guide_s3_minio_title,
        summaryRes = R.string.rclone_guide_s3_minio_summary,
        stepsRes = listOf(R.string.rclone_guide_s3_minio_step1, R.string.rclone_guide_s3_step_bucket),
        fields = listOf(
            GuideField("endpoint", R.string.rclone_field_endpoint, R.string.rclone_hint_endpoint_minio, GuideFieldKind.URL),
            accessKeyId(),
            secretAccessKey(),
            GuideField("region", R.string.rclone_field_region, R.string.rclone_hint_region_optional, GuideFieldKind.TEXT, required = false),
        ),
        oauth = false,
        fixedOptions = mapOf("provider" to "Minio", "env_auth" to "false"),
        suggestedRemoteName = "minio",
    )

    val S3_WASABI = ProviderGuide(
        id = "s3_wasabi",
        rcloneType = "s3",
        titleRes = R.string.rclone_guide_s3_wasabi_title,
        summaryRes = R.string.rclone_guide_s3_wasabi_summary,
        stepsRes = listOf(R.string.rclone_guide_s3_wasabi_step1, R.string.rclone_guide_s3_step_bucket),
        fields = listOf(
            accessKeyId(),
            secretAccessKey(),
            GuideField("endpoint", R.string.rclone_field_endpoint, R.string.rclone_hint_endpoint_wasabi, GuideFieldKind.TEXT, defaultValue = "s3.wasabisys.com"),
            GuideField("region", R.string.rclone_field_region, R.string.rclone_hint_region_optional, GuideFieldKind.TEXT, required = false),
        ),
        oauth = false,
        fixedOptions = mapOf("provider" to "Wasabi", "env_auth" to "false"),
        suggestedRemoteName = "wasabi",
    )

    val S3_R2 = ProviderGuide(
        id = "s3_r2",
        rcloneType = "s3",
        titleRes = R.string.rclone_guide_s3_r2_title,
        summaryRes = R.string.rclone_guide_s3_r2_summary,
        stepsRes = listOf(R.string.rclone_guide_s3_r2_step1, R.string.rclone_guide_s3_r2_step2, R.string.rclone_guide_s3_step_bucket),
        fields = listOf(
            GuideField("endpoint", R.string.rclone_field_endpoint, R.string.rclone_hint_endpoint_r2, GuideFieldKind.URL),
            accessKeyId(),
            secretAccessKey(),
        ),
        oauth = false,
        fixedOptions = mapOf("provider" to "Cloudflare", "env_auth" to "false", "region" to "auto", "acl" to "private"),
        suggestedRemoteName = "r2",
    )

    val B2 = ProviderGuide(
        id = "b2",
        rcloneType = "b2",
        titleRes = R.string.rclone_guide_b2_title,
        summaryRes = R.string.rclone_guide_b2_summary,
        stepsRes = listOf(R.string.rclone_guide_b2_step1, R.string.rclone_guide_b2_step2),
        fields = listOf(
            GuideField("account", R.string.rclone_field_b2_account, R.string.rclone_hint_b2_account, GuideFieldKind.TEXT),
            GuideField("key", R.string.rclone_field_b2_key, R.string.rclone_hint_b2_key, GuideFieldKind.PASSWORD, secret = true),
        ),
        oauth = false,
        suggestedRemoteName = "b2",
    )

    val GOOGLE_DRIVE = ProviderGuide(
        id = "drive",
        rcloneType = "drive",
        titleRes = R.string.rclone_guide_drive_title,
        summaryRes = R.string.rclone_guide_drive_summary,
        stepsRes = OAUTH_STEPS + R.string.rclone_guide_drive_step_scope,
        fields = listOf(
            token(),
            GuideField("scope", R.string.rclone_field_scope, R.string.rclone_hint_drive_scope, GuideFieldKind.CHOICE, defaultValue = "drive", choices = listOf("drive", "drive.file")),
            GuideField("root_folder_id", R.string.rclone_field_root_folder_id, R.string.rclone_hint_root_folder_id, GuideFieldKind.TEXT, required = false),
            clientId(),
            clientSecret(),
        ),
        oauth = true,
        suggestedRemoteName = "gdrive",
    )

    val ONEDRIVE = ProviderGuide(
        id = "onedrive",
        rcloneType = "onedrive",
        titleRes = R.string.rclone_guide_onedrive_title,
        summaryRes = R.string.rclone_guide_onedrive_summary,
        stepsRes = OAUTH_STEPS + R.string.rclone_guide_onedrive_step_drive,
        fields = listOf(
            token(),
            GuideField("drive_id", R.string.rclone_field_drive_id, R.string.rclone_hint_drive_id, GuideFieldKind.TEXT),
            GuideField("drive_type", R.string.rclone_field_drive_type, R.string.rclone_hint_drive_type, GuideFieldKind.CHOICE, defaultValue = "personal", choices = listOf("personal", "business", "documentLibrary")),
            clientId(),
            clientSecret(),
        ),
        oauth = true,
        suggestedRemoteName = "onedrive",
    )

    val DROPBOX = ProviderGuide(
        id = "dropbox",
        rcloneType = "dropbox",
        titleRes = R.string.rclone_guide_dropbox_title,
        summaryRes = R.string.rclone_guide_dropbox_summary,
        stepsRes = OAUTH_STEPS,
        fields = listOf(token(), clientId(), clientSecret()),
        oauth = true,
        suggestedRemoteName = "dropbox",
    )

    val MEGA = ProviderGuide(
        id = "mega",
        rcloneType = "mega",
        titleRes = R.string.rclone_guide_mega_title,
        summaryRes = R.string.rclone_guide_mega_summary,
        stepsRes = listOf(R.string.rclone_guide_mega_step1, R.string.rclone_guide_mega_step2),
        fields = listOf(
            GuideField("user", R.string.rclone_field_email, R.string.rclone_hint_mega_user, GuideFieldKind.EMAIL),
            GuideField("pass", R.string.rclone_field_password, R.string.rclone_hint_password, GuideFieldKind.PASSWORD, secret = true, obscure = true),
        ),
        oauth = false,
        suggestedRemoteName = "mega",
    )

    val PCLOUD = ProviderGuide(
        id = "pcloud",
        rcloneType = "pcloud",
        titleRes = R.string.rclone_guide_pcloud_title,
        summaryRes = R.string.rclone_guide_pcloud_summary,
        stepsRes = OAUTH_STEPS + R.string.rclone_guide_pcloud_step_region,
        fields = listOf(
            token(),
            GuideField("hostname", R.string.rclone_field_pcloud_hostname, R.string.rclone_hint_pcloud_hostname, GuideFieldKind.CHOICE, defaultValue = "api.pcloud.com", choices = listOf("api.pcloud.com", "eapi.pcloud.com")),
            clientId(),
            clientSecret(),
        ),
        oauth = true,
        suggestedRemoteName = "pcloud",
    )

    val BOX = ProviderGuide(
        id = "box",
        rcloneType = "box",
        titleRes = R.string.rclone_guide_box_title,
        summaryRes = R.string.rclone_guide_box_summary,
        stepsRes = OAUTH_STEPS + R.string.rclone_guide_box_step_rotation,
        fields = listOf(
            token(),
            GuideField("box_sub_type", R.string.rclone_field_box_sub_type, R.string.rclone_hint_box_sub_type, GuideFieldKind.CHOICE, defaultValue = "user", choices = listOf("user", "enterprise")),
            clientId(),
            clientSecret(),
        ),
        oauth = true,
        suggestedRemoteName = "box",
    )

    val SFTP = ProviderGuide(
        id = "sftp",
        rcloneType = "sftp",
        titleRes = R.string.rclone_guide_sftp_title,
        summaryRes = R.string.rclone_guide_sftp_summary,
        stepsRes = listOf(R.string.rclone_guide_sftp_step1, R.string.rclone_guide_sftp_step2),
        fields = listOf(
            GuideField("host", R.string.rclone_field_host, R.string.rclone_hint_host, GuideFieldKind.TEXT),
            GuideField("port", R.string.rclone_field_port, R.string.rclone_hint_port, GuideFieldKind.NUMBER, defaultValue = "22"),
            GuideField("user", R.string.rclone_field_username, R.string.rclone_hint_username, GuideFieldKind.TEXT),
            GuideField("pass", R.string.rclone_field_password, R.string.rclone_hint_sftp_password, GuideFieldKind.PASSWORD, secret = true, required = false, obscure = true),
            GuideField("key_pem", R.string.rclone_field_key_pem, R.string.rclone_hint_key_pem, GuideFieldKind.MULTILINE_SECRET, secret = true, required = false),
            GuideField("key_file_pass", R.string.rclone_field_key_passphrase, R.string.rclone_hint_key_passphrase, GuideFieldKind.PASSWORD, secret = true, required = false, obscure = true),
        ),
        oauth = false,
        atLeastOneOf = listOf(setOf("pass", "key_pem")),
        suggestedRemoteName = "sftp",
    )

    val WEBDAV_NEXTCLOUD = ProviderGuide(
        id = "webdav_nextcloud",
        rcloneType = "webdav",
        titleRes = R.string.rclone_guide_nextcloud_title,
        summaryRes = R.string.rclone_guide_nextcloud_summary,
        stepsRes = listOf(R.string.rclone_guide_nextcloud_step1, R.string.rclone_guide_nextcloud_step2),
        fields = listOf(
            GuideField("url", R.string.rclone_field_url, R.string.rclone_hint_nextcloud_url, GuideFieldKind.URL),
            GuideField("user", R.string.rclone_field_username, R.string.rclone_hint_username, GuideFieldKind.TEXT),
            GuideField("pass", R.string.rclone_field_password, R.string.rclone_hint_nextcloud_password, GuideFieldKind.PASSWORD, secret = true, obscure = true),
        ),
        oauth = false,
        fixedOptions = mapOf("vendor" to "nextcloud"),
        suggestedRemoteName = "nextcloud",
    )

    /** Every guide, in the order the setup UI lists them. */
    val all: List<ProviderGuide> = listOf(
        S3_AWS, S3_MINIO, S3_WASABI, S3_R2, B2, GOOGLE_DRIVE, ONEDRIVE, DROPBOX, MEGA, PCLOUD, BOX, SFTP, WEBDAV_NEXTCLOUD,
    )

    fun byId(id: String): ProviderGuide? = all.firstOrNull { it.id == id }
}
