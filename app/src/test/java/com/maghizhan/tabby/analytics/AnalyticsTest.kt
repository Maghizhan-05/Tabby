package com.maghizhan.tabby.analytics

import com.maghizhan.tabby.BuildConfig
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `consent off is the hard gate for every event`() = runTest {
        val consent = InMemoryAnalyticsConsentStore(false)
        val buffer = InMemoryAnalyticsBuffer()
        val tracker = AnalyticsTracker(consent, buffer, userId = { "user-1" })

        AnalyticsEvent.samples().forEach { tracker.track(it) }

        assertTrue(buffer.peek().isEmpty())
    }

    @Test
    fun `turning consent off drops the queued buffer`() = runTest {
        val consent = InMemoryAnalyticsConsentStore(true)
        val buffer = InMemoryAnalyticsBuffer()
        val tracker = AnalyticsTracker(consent, buffer, userId = { "user-1" })

        tracker.track(AnalyticsEvent.AppOpened(coldStart = true))
        assertEquals(1, buffer.peek().size)

        tracker.setConsent(false)

        assertFalse(consent.isEnabled())
        assertTrue(buffer.peek().isEmpty())
    }

    @Test
    fun `serialized allowlist cannot carry pii or an exact amount`() {
        val forbidden = listOf(
            "private lunch note",
            "Arjun Example",
            "Food Secret",
            "person@example.com",
            "8248783665",
            "1234.56"
        )

        AnalyticsEvent.samples().forEach { event ->
            val raw = json.encodeToString(event.toRecord("user-1", "test"))
            forbidden.forEach { probe ->
                assertFalse("$event leaked $probe in $raw", raw.contains(probe, ignoreCase = true))
            }
        }
    }

    @Test
    fun `amount bucket boundaries do not expose the amount`() {
        assertEquals(AmountBucket.LT_100, AmountBucket.from(BigDecimal("99.99")))
        assertEquals(AmountBucket.FROM_100_TO_1K, AmountBucket.from(BigDecimal("100")))
        assertEquals(AmountBucket.FROM_100_TO_1K, AmountBucket.from(BigDecimal("999.99")))
        assertEquals(AmountBucket.FROM_1K_TO_10K, AmountBucket.from(BigDecimal("1000")))
        assertEquals(AmountBucket.GTE_10K, AmountBucket.from(BigDecimal("10000")))
    }

    @Test
    fun `buffer caps at five hundred and drops oldest`() = runTest {
        val buffer = InMemoryAnalyticsBuffer(capacity = 500)
        repeat(501) { index ->
            buffer.append(
                AnalyticsRecord(
                    userId = "user-1",
                    name = "screen_viewed",
                    occurredAt = index.toLong(),
                    appVersion = BuildConfig.VERSION_NAME,
                    props = mapOf("screen" to "home")
                )
            )
        }

        val rows = buffer.peek()
        assertEquals(500, rows.size)
        assertEquals(1L, rows.first().occurredAt)
        assertEquals(500L, rows.last().occurredAt)
    }

    @Test
    fun `upload failure never escapes and keeps rows for retry`() = runTest {
        val consent = InMemoryAnalyticsConsentStore(true)
        val buffer = InMemoryAnalyticsBuffer()
        val tracker = AnalyticsTracker(consent, buffer, userId = { "user-1" })
        tracker.track(AnalyticsEvent.AppOpened(coldStart = true))

        val uploader = AnalyticsUploader(buffer) { error("network down") }
        uploader.flushQuietly()

        assertEquals(1, buffer.peek().size)
    }
}
