package com.maghizhan.tabby.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The widget's category ring.
 *
 * Rasterised rather than drawn as a composable because Glance marshals its UI
 * to the launcher as a `RemoteViews` tree, which has no canvas primitive. These
 * tests pin the properties that matter once drawing happens in a bitmap: the
 * caller can tell "nothing to draw" from "drawn", the bitmap is the size that
 * was asked for, and ring colours stay aligned with the legend's.
 */
@RunWith(RobolectricTestRunner::class)
// NATIVE graphics: Robolectric's default LEGACY canvas is a no-op shim that
// records nothing, so a bitmap drawn under it stays blank and the "did it
// actually draw" assertion below could never pass. NATIVE runs real Skia.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetRingTest {

    private fun slice(category: String, amount: String) =
        WidgetCategorySlice(category = category, amount = amount)

    @Test
    fun `a ring is rendered at the requested size`() {
        val bitmap = WidgetRing.render(
            slices = listOf(slice("Food", "40"), slice("Transport", "60")),
            sizePx = 120,
            strokePx = 22f
        )

        assertNotNull("two positive slices must draw a ring", bitmap)
        assertEquals(120, bitmap!!.width)
        assertEquals(120, bitmap.height)
    }

    /**
     * Null rather than a blank bitmap, so the widget can fall back to the
     * total instead of showing an empty square where a chart should be.
     */
    @Test
    fun `no slices renders nothing`() {
        assertNull(WidgetRing.render(slices = emptyList(), sizePx = 120, strokePx = 22f))
    }

    /** Zero and negative totals are not drawable, and must not divide by zero. */
    @Test
    fun `slices totalling zero render nothing`() {
        assertNull(
            WidgetRing.render(
                slices = listOf(slice("Food", "0"), slice("Transport", "0")),
                sizePx = 120,
                strokePx = 22f
            )
        )
    }

    /** A single category still draws: it is a full ring, not an absent one. */
    @Test
    fun `one slice still renders a ring`() {
        val bitmap = WidgetRing.render(
            slices = listOf(slice("Food", "40")),
            sizePx = 100,
            strokePx = 19f
        )

        assertNotNull("a sole category must still draw its ring", bitmap)
        assertTrue("the ring must have drawn some pixels", bitmap!!.hasNonZeroPixels())
    }

    /** A malformed stored amount is ignored rather than crashing the widget. */
    @Test
    fun `an unparseable amount is skipped`() {
        val bitmap = WidgetRing.render(
            slices = listOf(slice("Food", "not-a-number"), slice("Transport", "60")),
            sizePx = 100,
            strokePx = 19f
        )

        assertNotNull("the remaining valid slice must still draw", bitmap)
    }

    /** A zero or negative size cannot allocate a bitmap. */
    @Test
    fun `a non-positive size renders nothing`() {
        assertNull(
            WidgetRing.render(
                slices = listOf(slice("Food", "40")),
                sizePx = 0,
                strokePx = 8f
            )
        )
    }

    /**
     * The widget legend and the in-app chart index the same palette, so a row's
     * dot matches its arc. Were these to diverge, the ring would be actively
     * misleading rather than merely decorative.
     */
    @Test
    fun `ring colours are shared with the in-app chart`() {
        assertEquals(
            com.maghizhan.tabby.ui.theme.TabbyPalette.ringColors[0],
            com.maghizhan.tabby.ui.home.ringColor(0)
        )
        assertEquals(
            com.maghizhan.tabby.ui.theme.TabbyPalette.ringColors[2],
            com.maghizhan.tabby.ui.home.ringColor(2)
        )
    }

    private fun android.graphics.Bitmap.hasNonZeroPixels(): Boolean {
        for (y in 0 until height step 4) {
            for (x in 0 until width step 4) {
                if (getPixel(x, y) != 0) return true
            }
        }
        return false
    }
}
