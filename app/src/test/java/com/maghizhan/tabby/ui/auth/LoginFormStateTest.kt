package com.maghizhan.tabby.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The login form's validation.
 *
 * These assertions exist to protect the three-state router: the form must be
 * able to fail — bad email, mismatched passwords, a rejected sign-in — without
 * any of it becoming a router transition.
 */
class LoginFormStateTest {

    @Test
    fun `a fresh form cannot submit`() {
        assertFalse(LoginFormState().canSubmit)
    }

    @Test
    fun `email needs a local part and a dotted domain`() {
        assertFalse(LoginFormState(email = "nobody").isEmailValid)
        assertFalse(LoginFormState(email = "@example.com").isEmailValid)
        assertFalse(LoginFormState(email = "a@example").isEmailValid)
        assertFalse(LoginFormState(email = "a@.com").isEmailValid)
        assertFalse(LoginFormState(email = "a@example.").isEmailValid)
        assertTrue(LoginFormState(email = "a@example.com").isEmailValid)
    }

    @Test
    fun `email is trimmed before validation`() {
        // A trailing space from autocomplete must not block the button.
        assertTrue(LoginFormState(email = "  a@example.com  ").isEmailValid)
    }

    @Test
    fun `password must reach the backend minimum of six`() {
        assertFalse(LoginFormState(password = "12345").isPasswordValid)
        assertTrue(LoginFormState(password = "123456").isPasswordValid)
    }

    @Test
    fun `sign-in submits without a confirmation field`() {
        val form = LoginFormState(
            email = "a@example.com",
            password = "secret1",
            mode = AuthMode.SIGN_IN
        )
        assertTrue(form.canSubmit)
    }

    @Test
    fun `create-account requires a matching confirmation`() {
        val base = LoginFormState(
            email = "a@example.com",
            password = "secret1",
            mode = AuthMode.CREATE_ACCOUNT
        )
        assertFalse("an empty confirmation must not submit", base.canSubmit)
        assertFalse(base.copy(confirmPassword = "secret2").canSubmit)
        assertTrue(base.copy(confirmPassword = "secret1").canSubmit)
    }

    @Test
    fun `confirmation error stays hidden until something is typed`() {
        val base = LoginFormState(
            email = "a@example.com",
            password = "secret1",
            mode = AuthMode.CREATE_ACCOUNT
        )
        // Nagging before the user has typed anything is noise, not feedback.
        assertNull(base.confirmPasswordError)
        assertNotNull(base.copy(confirmPassword = "s").confirmPasswordError)
        assertNull(base.copy(confirmPassword = "secret1").confirmPasswordError)
    }

    @Test
    fun `confirmation is irrelevant in sign-in mode`() {
        val form = LoginFormState(
            email = "a@example.com",
            password = "secret1",
            confirmPassword = "totally-different",
            mode = AuthMode.SIGN_IN
        )
        assertNull(form.confirmPasswordError)
        assertTrue(form.canSubmit)
    }

    @Test
    fun `a busy form cannot submit twice`() {
        val form = LoginFormState(
            email = "a@example.com",
            password = "secret1",
            isBusy = true
        )
        // Double-submitting a sign-in would start two auth intents and the
        // later one's result could clobber the earlier.
        assertFalse(form.canSubmit)
    }

    @Test
    fun `cta title reflects mode and busy state`() {
        assertEquals("Enter Tabby", LoginFormState().primaryCtaTitle)
        assertEquals(
            "Create Account",
            LoginFormState(mode = AuthMode.CREATE_ACCOUNT).primaryCtaTitle
        )
        assertEquals("Signing in", LoginFormState(isBusy = true).primaryCtaTitle)
        assertEquals(
            "Creating account",
            LoginFormState(mode = AuthMode.CREATE_ACCOUNT, isBusy = true).primaryCtaTitle
        )
    }
}
