package ca.devdcs.backup.core.credits

import androidx.annotation.StringRes
import ca.devdcs.backup.R

/**
 * A software license referenced by one or more [OpenSourceCredit] entries. Modelled as a value
 * type rather than an enum so that unusual combinations (dual licensing, license exceptions) can
 * be expressed with an exact SPDX expression while still sharing a single instance across credits.
 *
 * @property spdxId SPDX license identifier or expression, e.g. `BSD-3-Clause OR GPL-2.0-only`.
 * @property nameRes Localized human-readable license name shown in the credits UI.
 * @property url Canonical location of the full license text.
 */
data class OssLicense(
    val spdxId: String,
    @StringRes val nameRes: Int,
    val url: String,
) {
    companion object {
        val APACHE_2_0 = OssLicense(
            spdxId = "Apache-2.0",
            nameRes = R.string.license_apache_2_0_name,
            url = "https://www.apache.org/licenses/LICENSE-2.0",
        )

        val MIT = OssLicense(
            spdxId = "MIT",
            nameRes = R.string.license_mit_name,
            url = "https://opensource.org/license/mit",
        )

        val GPL_2_0_ONLY = OssLicense(
            spdxId = "GPL-2.0-only",
            nameRes = R.string.license_gpl_2_0_only_name,
            url = "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html",
        )

        val GPL_3_0 = OssLicense(
            spdxId = "GPL-3.0-or-later",
            nameRes = R.string.license_gpl_3_0_name,
            url = "https://www.gnu.org/licenses/gpl-3.0.html",
        )

        val LGPL_2_1 = OssLicense(
            spdxId = "LGPL-2.1-or-later",
            nameRes = R.string.license_lgpl_2_1_name,
            url = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html",
        )

        val BSD_3_CLAUSE = OssLicense(
            spdxId = "BSD-3-Clause",
            nameRes = R.string.license_bsd_3_clause_name,
            url = "https://opensource.org/license/bsd-3-clause",
        )

        val ZERO_BSD = OssLicense(
            spdxId = "0BSD",
            nameRes = R.string.license_zero_bsd_name,
            url = "https://opensource.org/license/0bsd",
        )

        val ZLIB = OssLicense(
            spdxId = "Zlib",
            nameRes = R.string.license_zlib_name,
            url = "https://opensource.org/license/zlib",
        )

        /** OpenSSL 3.x is Apache-2.0; kept distinct so the credits link to OpenSSL's own license page. */
        val OPENSSL_APACHE_2_0 = OssLicense(
            spdxId = "Apache-2.0",
            nameRes = R.string.license_openssl_apache_2_0_name,
            url = "https://www.openssl.org/source/license.html",
        )

        /** Zstandard's dual license: the user may pick either. */
        val DUAL_BSD_GPL = OssLicense(
            spdxId = "BSD-3-Clause OR GPL-2.0-only",
            nameRes = R.string.license_dual_bsd_gpl_name,
            url = "https://github.com/facebook/zstd/blob/dev/LICENSE",
        )

        /** 7-Zip: LGPL for the code base, with the RAR decompression code under its own restriction. */
        val LGPL_2_1_WITH_UNRAR_RESTRICTION = OssLicense(
            spdxId = "LGPL-2.1-or-later WITH unRAR-restriction",
            nameRes = R.string.license_lgpl_2_1_with_unrar_restriction_name,
            url = "https://www.7-zip.org/license.txt",
        )

        /** BLAKE3 reference material: public domain (CC0) or Apache-2.0 at the user's option. */
        val CC0_OR_APACHE_2_0 = OssLicense(
            spdxId = "CC0-1.0 OR Apache-2.0",
            nameRes = R.string.license_cc0_or_apache_2_0_name,
            url = "https://github.com/BLAKE3-team/BLAKE3/blob/master/LICENSE_A2",
        )
    }
}
