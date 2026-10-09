package com.maghizhan.tabby.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down the claim the typography wiring rests on: that a plain `Text`
 * inherits Inter without the call site asking for it.
 *
 * This is the assumption a review challenged, and it is not self-evident --
 * Material applies ONE slot (`bodyLarge`) as the ambient `LocalTextStyle` and
 * leaves the rest inert. If a future Compose release stops providing it, ~93
 * `Text` call sites silently revert to the platform font with nothing failing.
 * These tests fail instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypographyInheritanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `MaterialTheme provides bodyLarge as the ambient text style`() {
        var ambient: TextStyle? = null
        composeRule.setContent {
            TabbyTheme { ambient = LocalTextStyle.current }
        }
        composeRule.waitForIdle()

        assertEquals(
            "A plain Text must inherit Inter; if this fails every unstyled " +
                "call site has silently reverted to the platform font.",
            InterFamily,
            ambient?.fontFamily
        )
    }

    @Test
    fun `theme installs the Tabby typography`() {
        var resolved: TextStyle? = null
        composeRule.setContent {
            TabbyTheme { resolved = MaterialTheme.typography.displayLarge }
        }
        composeRule.waitForIdle()

        // Hero amounts are Nunito, the SF Rounded stand-in.
        assertEquals(NunitoFamily, resolved?.fontFamily)
    }
}
