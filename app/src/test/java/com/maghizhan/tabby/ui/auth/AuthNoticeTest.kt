package com.maghizhan.tabby.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Auth failures must never put a raw client exception on screen.
 *
 * The Supabase client embeds the whole failed request in its exception message,
 * including `Authorization: Bearer <publishable key>` and the full URL. Shown
 * verbatim, that lands in the user's screenshots and bug reports. Caught on a
 * device during Checkpoint 4 verification; these tests keep it fixed.
 */
class AuthNoticeTest {

    /** A redacted shape of what the client actually threw on a bad password. */
    private val leakyMessage = """
        invalid_credentials
        Invalid login credentials: invalid_credentials
        URL: https://project.supabase.co/auth/v1/token?grant_type=password&redirect_to=com.maghizhan.tabby%3A%2F%2Fauth-callback
        Headers: [Authorization=[Bearer sb_publishable_EXAMPLEKEY], apikey=[sb_publishable_EXAMPLEKEY]]
    """.trimIndent()

    @Test
    fun `a leaky client error never reaches the user verbatim`() {
        val notice = authNoticeFor(RuntimeException(leakyMessage), AuthMode.SIGN_IN)

        assertFalse("URL leaked", notice.contains("http", ignoreCase = true))
        assertFalse("auth header leaked", notice.contains("Authorization", ignoreCase = true))
        assertFalse("bearer token leaked", notice.contains("Bearer", ignoreCase = true))
        assertFalse("api key leaked", notice.contains("sb_publishable", ignoreCase = true))
        assertFalse("header dump leaked", notice.contains("Headers", ignoreCase = true))
    }

    @Test
    fun `bad credentials get an actionable sentence`() {
        assertEquals(
            "That email and password don't match an account.",
            authNoticeFor(RuntimeException(leakyMessage), AuthMode.SIGN_IN)
        )
    }

    @Test
    fun `a duplicate signup points the user at sign-in`() {
        val notice = authNoticeFor(
            RuntimeException("user_already_exists: ..."),
            AuthMode.CREATE_ACCOUNT
        )
        assertTrue(notice.contains("already exists"))
    }

    @Test
    fun `a weak password is reported as a password problem`() {
        val notice = authNoticeFor(RuntimeException("weak_password"), AuthMode.CREATE_ACCOUNT)
        assertTrue(notice.contains("password"))
    }

    @Test
    fun `an offline device is told it is offline, not that its password is wrong`() {
        val notice = authNoticeFor(IOException("Failed to connect to /10.0.2.2"), AuthMode.SIGN_IN)
        assertTrue(notice.contains("connection", ignoreCase = true))
    }

    @Test
    fun `an unrecognised failure falls back by mode`() {
        assertEquals(
            "Could not sign in. Try again.",
            authNoticeFor(RuntimeException("something nobody mapped"), AuthMode.SIGN_IN)
        )
        assertEquals(
            "Could not create your account. Try again.",
            authNoticeFor(RuntimeException("something nobody mapped"), AuthMode.CREATE_ACCOUNT)
        )
    }

    @Test
    fun `an exception with no message still yields a usable notice`() {
        val notice = authNoticeFor(RuntimeException(), AuthMode.SIGN_IN)
        assertTrue(notice.isNotBlank())
        assertFalse(notice.contains("null", ignoreCase = true))
    }
}
