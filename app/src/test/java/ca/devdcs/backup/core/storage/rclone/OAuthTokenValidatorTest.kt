package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.storage.rclone.OAuthTokenValidator.Problem
import ca.devdcs.backup.core.storage.rclone.OAuthTokenValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthTokenValidatorTest {

    private fun problem(input: String): Problem = (OAuthTokenValidator.validate(input) as Result.Invalid).problem

    @Test
    fun `accepts rclone authorize output with markers and whitespace`() {
        val input = """
            2024/01/01 NOTICE: Got code
            Paste the following into your remote machine --->
            {
              "access_token": "abc",
              "token_type": "Bearer",
              "refresh_token": "def",
              "expiry": "2026-10-19T13:14:15.5+00:00"
            }
            <---End paste
        """.trimIndent()
        val valid = OAuthTokenValidator.validate(input) as Result.Valid
        assertEquals("""{"access_token":"abc","token_type":"Bearer","refresh_token":"def","expiry":"2026-10-19T13:14:15.5+00:00"}""", valid.normalizedJson)
        assertTrue(valid.hasRefreshToken)
        assertEquals("2026-10-19T13:14:15.5+00:00", valid.expiry)
    }

    @Test
    fun `accepts go zero expiry and missing refresh token`() {
        val valid = OAuthTokenValidator.validate("""{"access_token":"a","token_type":"bearer","expiry":"0001-01-01T00:00:00Z"}""") as Result.Valid
        assertFalse(valid.hasRefreshToken)
    }

    @Test
    fun `rejects bad input with a precise problem`() {
        assertEquals(Problem.EMPTY, problem("   "))
        assertEquals(Problem.NOT_JSON, problem("hello"))
        assertEquals(Problem.NOT_JSON, problem("{not: json,}"))
        assertEquals(Problem.MISSING_ACCESS_TOKEN, problem("""{"token_type":"bearer"}"""))
        assertEquals(Problem.MISSING_ACCESS_TOKEN, problem("""{"access_token":"","token_type":"bearer"}"""))
        assertEquals(Problem.MISSING_TOKEN_TYPE, problem("""{"access_token":"a"}"""))
        assertEquals(Problem.BAD_EXPIRY, problem("""{"access_token":"a","token_type":"b","expiry":"tomorrow"}"""))
    }

    @Test
    fun `only the outermost object is used`() {
        assertEquals(Problem.NOT_JSON, problem("[1, 2]"))
        assertEquals(Problem.MISSING_TOKEN_TYPE, problem("""[{"access_token":"a"}]"""))
    }
}
