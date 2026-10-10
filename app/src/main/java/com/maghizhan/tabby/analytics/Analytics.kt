package com.maghizhan.tabby.analytics

import android.content.Context
import com.maghizhan.tabby.BuildConfig
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AnalyticsRecord(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("app_version") val appVersion: String,
    val props: Map<String, String> = emptyMap()
)

enum class AmountBucket(val wire: String) {
    LT_100("lt_100"),
    FROM_100_TO_1K("100_1k"),
    FROM_1K_TO_10K("1k_10k"),
    GTE_10K("gte_10k");

    companion object {
        fun from(amount: BigDecimal): AmountBucket = when {
            amount < BigDecimal("100") -> LT_100
            amount < BigDecimal("1000") -> FROM_100_TO_1K
            amount < BigDecimal("10000") -> FROM_1K_TO_10K
            else -> GTE_10K
        }
    }
}

enum class AnalyticsScreen(val wire: String) {
    HOME("home"), FRIENDS("friends"), PROFILE("profile"),
    QUICK_ENTRY("quick_entry"), WIDGET_CONFIG("widget_config"), ANALYTICS("analytics")
}

enum class AnalyticsPeriod(val wire: String) {
    TODAY("today"), WEEK("week"), MONTH("month"), YEAR("year"),
    CATEGORY("category"), TREND("trend")
}

enum class WidgetShape(val wire: String) { WIDE("wide"), SQUARE("square") }
enum class SignInProvider(val wire: String) { EMAIL("email"), GOOGLE("google") }

sealed interface AnalyticsEvent {
    data class AppOpened(val coldStart: Boolean) : AnalyticsEvent
    data class ScreenViewed(val screen: AnalyticsScreen) : AnalyticsEvent
    data class ExpenseLogged(val hasNote: Boolean, val amountBucket: AmountBucket) : AnalyticsEvent
    data object ExpenseEdited : AnalyticsEvent
    data object ExpenseDeleted : AnalyticsEvent
    data object CategoryCreated : AnalyticsEvent
    data object CategoryDeleted : AnalyticsEvent
    data object FriendCreated : AnalyticsEvent
    data object FriendUpdated : AnalyticsEvent
    data class PeriodChanged(val period: AnalyticsPeriod) : AnalyticsEvent
    data class WidgetPlaced(val shape: WidgetShape) : AnalyticsEvent
    data class WidgetTapped(val shape: WidgetShape) : AnalyticsEvent
    data class WidgetPeriodCycled(val shape: WidgetShape) : AnalyticsEvent
    data class SyncCompleted(val durationMs: Long, val pushed: Int, val pulled: Int) : AnalyticsEvent
    data class SignIn(val provider: SignInProvider) : AnalyticsEvent
    data object AccountDeleted : AnalyticsEvent

    companion object {
        /** Every variant, used by the PII allowlist regression test. */
        fun samples(): List<AnalyticsEvent> = listOf(
            AppOpened(true), ScreenViewed(AnalyticsScreen.HOME),
            ExpenseLogged(true, AmountBucket.FROM_1K_TO_10K), ExpenseEdited, ExpenseDeleted,
            CategoryCreated, CategoryDeleted, FriendCreated, FriendUpdated,
            PeriodChanged(AnalyticsPeriod.MONTH), WidgetPlaced(WidgetShape.SQUARE),
            WidgetTapped(WidgetShape.WIDE), WidgetPeriodCycled(WidgetShape.SQUARE),
            SyncCompleted(321, 2, 3), SignIn(SignInProvider.GOOGLE), AccountDeleted
        )
    }
}

fun AnalyticsEvent.toRecord(
    userId: String,
    appVersion: String = BuildConfig.VERSION_NAME,
    occurredAt: Long = Instant.now().toEpochMilli()
): AnalyticsRecord {
    val (name, props) = when (this) {
        is AnalyticsEvent.AppOpened -> "app_opened" to mapOf("cold_start" to coldStart.toString())
        is AnalyticsEvent.ScreenViewed -> "screen_viewed" to mapOf("screen" to screen.wire)
        is AnalyticsEvent.ExpenseLogged -> "expense_logged" to mapOf(
            "has_note" to hasNote.toString(), "amount_bucket" to amountBucket.wire
        )
        AnalyticsEvent.ExpenseEdited -> "expense_edited" to emptyMap()
        AnalyticsEvent.ExpenseDeleted -> "expense_deleted" to emptyMap()
        AnalyticsEvent.CategoryCreated -> "category_created" to emptyMap()
        AnalyticsEvent.CategoryDeleted -> "category_deleted" to emptyMap()
        AnalyticsEvent.FriendCreated -> "friend_created" to emptyMap()
        AnalyticsEvent.FriendUpdated -> "friend_updated" to emptyMap()
        is AnalyticsEvent.PeriodChanged -> "period_changed" to mapOf("period" to period.wire)
        is AnalyticsEvent.WidgetPlaced -> "widget_placed" to mapOf("shape" to shape.wire)
        is AnalyticsEvent.WidgetTapped -> "widget_tapped" to mapOf("shape" to shape.wire)
        is AnalyticsEvent.WidgetPeriodCycled -> "widget_period_cycled" to mapOf("shape" to shape.wire)
        is AnalyticsEvent.SyncCompleted -> "sync_completed" to mapOf(
            "duration_ms" to durationMs.coerceAtLeast(0).toString(),
            "pushed" to pushed.coerceAtLeast(0).toString(),
            "pulled" to pulled.coerceAtLeast(0).toString()
        )
        is AnalyticsEvent.SignIn -> "sign_in" to mapOf("provider" to provider.wire)
        AnalyticsEvent.AccountDeleted -> "account_deleted" to emptyMap()
    }
    return AnalyticsRecord(userId = userId, name = name, occurredAt = occurredAt, appVersion = appVersion, props = props)
}

interface AnalyticsConsentStore {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
    fun hasAnsweredDisclosure(): Boolean
    fun markDisclosureAnswered()
    fun clear()
}

class PreferencesAnalyticsConsentStore(context: Context) : AnalyticsConsentStore {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    override fun isEnabled() = preferences.getBoolean(KEY_ENABLED, false)
    override fun setEnabled(enabled: Boolean) = preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    override fun hasAnsweredDisclosure() = preferences.getBoolean(KEY_DISCLOSURE, false)
    override fun markDisclosureAnswered() = preferences.edit().putBoolean(KEY_DISCLOSURE, true).apply()
    override fun clear() = preferences.edit().clear().apply()

    private companion object {
        const val FILE = "tabby_analytics_consent"
        const val KEY_ENABLED = "enabled"
        const val KEY_DISCLOSURE = "disclosure_answered"
    }
}

class InMemoryAnalyticsConsentStore(private var enabled: Boolean = false) : AnalyticsConsentStore {
    private var answered = false
    override fun isEnabled() = enabled
    override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
    override fun hasAnsweredDisclosure() = answered
    override fun markDisclosureAnswered() { answered = true }
    override fun clear() { enabled = false; answered = false }
}

interface AnalyticsBuffer {
    suspend fun append(record: AnalyticsRecord)
    suspend fun peek(): List<AnalyticsRecord>
    suspend fun remove(ids: Set<String>)
    suspend fun clear()
}

class PreferencesAnalyticsBuffer(
    context: Context,
    private val capacity: Int = 500
) : AnalyticsBuffer {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    override suspend fun append(record: AnalyticsRecord) = mutex.withLock {
        val rows = read().plus(record).takeLast(capacity)
        write(rows)
    }
    override suspend fun peek(): List<AnalyticsRecord> = mutex.withLock { read() }
    override suspend fun remove(ids: Set<String>) = mutex.withLock { write(read().filterNot { it.id in ids }) }
    override suspend fun clear() = mutex.withLock { preferences.edit().clear().commit(); Unit }
    private fun read(): List<AnalyticsRecord> = preferences.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString<List<AnalyticsRecord>>(it) }.getOrNull() }
        .orEmpty()
    private fun write(rows: List<AnalyticsRecord>) {
        preferences.edit().putString(KEY, json.encodeToString(rows)).commit()
    }
    private companion object { const val FILE = "tabby_analytics_buffer"; const val KEY = "events" }
}

class InMemoryAnalyticsBuffer(private val capacity: Int = 500) : AnalyticsBuffer {
    private val rows = mutableListOf<AnalyticsRecord>()
    override suspend fun append(record: AnalyticsRecord) { rows += record; while (rows.size > capacity) rows.removeAt(0) }
    override suspend fun peek() = rows.toList()
    override suspend fun remove(ids: Set<String>) { rows.removeAll { it.id in ids } }
    override suspend fun clear() { rows.clear() }
}

class AnalyticsTracker(
    private val consent: AnalyticsConsentStore,
    private val buffer: AnalyticsBuffer,
    private val userId: () -> String?
) {
    suspend fun track(event: AnalyticsEvent) {
        if (!consent.isEnabled()) return
        val owner = userId()?.trim()?.takeIf(String::isNotEmpty) ?: return
        buffer.append(event.toRecord(owner))
    }

    suspend fun setConsent(enabled: Boolean) {
        consent.setEnabled(enabled)
        consent.markDisclosureAnswered()
        if (!enabled) buffer.clear()
    }

    fun isEnabled() = consent.isEnabled()
    fun hasAnsweredDisclosure() = consent.hasAnsweredDisclosure()
    suspend fun clear() { buffer.clear(); consent.clear() }
}

class AnalyticsUploader(
    private val buffer: AnalyticsBuffer,
    private val transport: suspend (List<AnalyticsRecord>) -> Unit
) {
    suspend fun flushQuietly() {
        val rows = buffer.peek()
        if (rows.isEmpty()) return
        try {
            transport(rows)
            buffer.remove(rows.mapTo(mutableSetOf()) { it.id })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Analytics is best-effort and must never affect product behaviour.
        }
    }
}
