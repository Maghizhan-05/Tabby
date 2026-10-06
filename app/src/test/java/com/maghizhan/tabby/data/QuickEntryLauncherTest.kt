package com.maghizhan.tabby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A quick-entry request must open the sheet exactly once.
 *
 * The defect: requests were a monotonic counter, and the collector opened the
 * sheet whenever the value was greater than zero. Once a single request had ever
 * been made the value stayed non-zero forever, so every later recreation — a
 * rotation, or the account-keyed authenticated tree being rebuilt — observed the
 * same non-zero value and reopened a sheet the user had already used and
 * dismissed. Requests are now consumable ids, and null means nothing is pending.
 */
class QuickEntryLauncherTest {

    @Before
    fun setUp() = QuickEntryLauncher.reset()

    @Test
    fun `nothing is pending before any request`() {
        assertNull(QuickEntryLauncher.requests.value)
    }

    @Test
    fun `a request becomes pending and is cleared once consumed`() {
        QuickEntryLauncher.request(42L)
        assertEquals(42L, QuickEntryLauncher.requests.value)

        QuickEntryLauncher.consume(42L)

        // Null rather than a sticky non-zero counter: this is what stops the
        // sheet reopening on the next rotation.
        assertNull("a consumed request must not remain pending", QuickEntryLauncher.requests.value)
    }

    @Test
    fun `two consecutive requests are two openings`() {
        QuickEntryLauncher.request(1L)
        QuickEntryLauncher.consume(1L)
        QuickEntryLauncher.request(2L)

        assertEquals(
            "a second tap must raise a new request rather than being a no-op",
            2L,
            QuickEntryLauncher.requests.value
        )
    }

    @Test
    fun `consuming a stale id does not swallow a newer request`() {
        QuickEntryLauncher.request(1L)
        // A newer request arrives while the first is still being handled.
        QuickEntryLauncher.request(2L)

        QuickEntryLauncher.consume(1L)

        assertEquals(
            "the newer request was discarded by the older one's acknowledgement",
            2L,
            QuickEntryLauncher.requests.value
        )
    }

    @Test
    fun `a request made before the UI exists is still pending when it collects`() {
        // The cold-start case: the shortcut or widget launches the app, so the
        // request is raised before the authenticated tree has composed.
        QuickEntryLauncher.request(7L)
        assertEquals(7L, QuickEntryLauncher.requests.value)
    }
}
