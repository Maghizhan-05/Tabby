package com.maghizhan.tabby.ui.common

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The "one row open at a time" rule for swipe-to-reveal lists.
 *
 * Worth testing without a UI harness because the bugs here are about IDENTITY,
 * not pixels: closing by the wrong key leaves a Delete button exposed in a list
 * the user has already scrolled away from.
 */
class SwipeRevealControllerTest {

    @Test
    fun `no row is open initially`() {
        assertNull(SwipeRevealController().openRow)
    }

    @Test
    fun `opening a row claims the reveal`() {
        val controller = SwipeRevealController()
        val row = Any()

        controller.open(row)

        assertSame(row, controller.openRow)
    }

    @Test
    fun `opening a second row takes the reveal from the first`() {
        val controller = SwipeRevealController()
        val first = Any()
        val second = Any()

        controller.open(first)
        controller.open(second)

        assertSame(second, controller.openRow)
        assertNotEquals(first, controller.openRow)
    }

    @Test
    fun `a row closing itself releases the reveal`() {
        val controller = SwipeRevealController()
        val row = Any()

        controller.open(row)
        controller.close(row)

        assertNull(controller.openRow)
    }

    @Test
    fun `a stale row cannot close the row that replaced it`() {
        // The ordering that makes identity matter: a row that lost the reveal
        // still runs its own close during recomposition. If close() were not
        // guarded, that late call would shut the row the user just opened.
        val controller = SwipeRevealController()
        val stale = Any()
        val current = Any()

        controller.open(stale)
        controller.open(current)
        controller.close(stale)

        assertSame(current, controller.openRow)
    }

    @Test
    fun `closeAll releases whichever row is open`() {
        val controller = SwipeRevealController()
        controller.open(Any())

        controller.closeAll()

        assertNull(controller.openRow)
    }

    @Test
    fun `closeAll on an already closed controller is harmless`() {
        val controller = SwipeRevealController()

        controller.closeAll()
        controller.closeAll()

        assertNull(controller.openRow)
    }
}
