package com.maghizhan.tabby.remote

import com.maghizhan.tabby.data.remote.OAuthCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The callback intent filter is exported, so any app on the device can deliver a
 * URL here. These pin that only our own callbacks are acted on, and that a
 * provider error is reported rather than silently hanging.
 */
class OAuthCallbackTest {

    @Test
    fun `a valid callback yields its authorization code`() {
        val result = OAuthCallback.parse("com.maghizhan.tabby://auth-callback?code=abc123")
        assertEquals(OAuthCallback.Result.Code("abc123"), result)
    }

    @Test
    fun `a callback with a path still yields its code`() {
        val result = OAuthCallback.parse("com.maghizhan.tabby://auth-callback/?code=abc123")
        assertEquals(OAuthCallback.Result.Code("abc123"), result)
    }

    @Test
    fun `a code delivered in the fragment is found`() {
        val result = OAuthCallback.parse("com.maghizhan.tabby://auth-callback#code=frag456")
        assertEquals(OAuthCallback.Result.Code("frag456"), result)
    }

    @Test
    fun `a foreign scheme is not treated as our callback`() {
        val result = OAuthCallback.parse("https://evil.test/auth-callback?code=abc123")
        assertEquals(OAuthCallback.Result.NotACallback, result)
    }

    /**
     * The guard that matters: a host merely PREFIXED by ours must not be
     * accepted, or `auth-callback.evil.test` would pass a naive startsWith.
     */
    @Test
    fun `a lookalike host is rejected`() {
        val result = OAuthCallback.parse("com.maghizhan.tabby://auth-callback.evil.test?code=abc")
        assertEquals(OAuthCallback.Result.NotACallback, result)
    }

    @Test
    fun `our callback without a code is invalid rather than ignored`() {
        val result = OAuthCallback.parse("com.maghizhan.tabby://auth-callback?state=xyz")
        assertTrue(result is OAuthCallback.Result.Invalid)
    }

    @Test
    fun `a provider error is surfaced with its description`() {
        val result = OAuthCallback.parse(
            "com.maghizhan.tabby://auth-callback?error=access_denied&error_description=User+declined"
        )
        assertEquals(
            OAuthCallback.Result.ProviderError("access_denied", "User declined"),
            result
        )
    }

    /** An error must win even when a code is also present. */
    @Test
    fun `an error takes precedence over a code`() {
        val result = OAuthCallback.parse(
            "com.maghizhan.tabby://auth-callback?code=abc&error=access_denied"
        )
        assertTrue(result is OAuthCallback.Result.ProviderError)
    }

    @Test
    fun `a null or blank url is not a callback`() {
        assertEquals(OAuthCallback.Result.NotACallback, OAuthCallback.parse(null))
        assertEquals(OAuthCallback.Result.NotACallback, OAuthCallback.parse("   "))
    }

    /** The redirect we register with Supabase must match the manifest filter. */
    @Test
    fun `the redirect url is built from the scheme and host`() {
        assertEquals("com.maghizhan.tabby://auth-callback", OAuthCallback.REDIRECT_URL)
        assertEquals("com.maghizhan.tabby", OAuthCallback.SCHEME)
        assertEquals("auth-callback", OAuthCallback.HOST)
    }

    @Test
    fun `percent encoded descriptions are decoded`() {
        val result = OAuthCallback.parse(
            "com.maghizhan.tabby://auth-callback?error=bad&error_description=not%20allowed"
        )
        assertEquals("not allowed", (result as OAuthCallback.Result.ProviderError).description)
    }
}
