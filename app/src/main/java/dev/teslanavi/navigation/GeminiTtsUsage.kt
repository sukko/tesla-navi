package dev.teslanavi.navigation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

enum class GeminiTtsModel(val id: String, val label: String) {
    PRIMARY("gemini-3.8-flash-lite-tts", "3.8 Flash-Lite TTS"),
    FALLBACK("gemini-3.1-flash-tts-preview", "3.1 Flash TTS"),
    FLASH("gemini-3.8-flash-tts", "3.8 Flash TTS")
}

data class GeminiModelUsage(val rpm: Int = 0, val rpd: Int = 0)
data class GeminiUsageState(
    val model: GeminiTtsModel = GeminiTtsModel.PRIMARY,
    val primary: GeminiModelUsage = GeminiModelUsage(),
    val fallback: GeminiModelUsage = GeminiModelUsage(),
    val flash: GeminiModelUsage = GeminiModelUsage(),
    val resetsAt: Long = 0,
    val reason: String? = null,
    val error: String? = null,
)

/** Conservative local request accounting. Project activity outside this app is handled by HTTP 429. */
class GeminiTtsUsage(
    private val prefs: SharedPreferences? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.getSharedPreferences("gemini-tts-usage", Context.MODE_PRIVATE))
    data class Ticket(val model: GeminiTtsModel, val day: String, val startedAt: Long)
    private data class Counter(var daily: Int = 0, val times: MutableList<Long> = mutableListOf(), var blockedUntil: Long = 0)
    private val counters = GeminiTtsModel.entries.associateWith { Counter() }
    private var day = date(clock())
    private var reason: String? = null
    private var selectedIndex = 0
    private var storageReady = true
    private val mutable = MutableStateFlow(GeminiUsageState())
    val state = mutable.asStateFlow()

    init {
        prefs?.getString("usage", null)?.let { saved ->
            runCatching {
                val root = JSONObject(saved)
                day = root.getString("day")
                reason = root.optString("reason").takeIf(String::isNotBlank)
                selectedIndex = root.optInt("selectedIndex").coerceIn(0, GeminiTtsModel.entries.lastIndex)
                for ((model, counter) in counters) {
                    val data = root.optJSONObject(model.name) ?: continue
                    counter.daily = data.optInt("daily").coerceIn(0, 100_000)
                    counter.blockedUntil = data.optLong("blockedUntil")
                    val times = data.optJSONArray("times") ?: JSONArray()
                    for (i in 0 until minOf(times.length(), 100)) counter.times += times.getLong(i)
                }
            }.onFailure {
                // A corrupt ledger must not turn an unknown daily allowance into fresh requests.
                day = date(clock()); reason = "사용량 기록 확인 필요"; selectedIndex = GeminiTtsModel.entries.lastIndex
                counters.values.forEach { it.daily = DAILY_LIMIT; it.blockedUntil = nextReset(clock()) }
            }
        }
        refresh()
    }

    @Synchronized fun refresh() = refreshAt(clock())

    private fun refreshAt(now: Long) {
        val changedDay = date(now) != day
        if (changedDay) {
            day = date(now); reason = null; selectedIndex = 0
            counters.values.forEach { it.daily = 0; it.blockedUntil = 0 }
        }
        for (counter in counters.values) counter.times.removeAll { it <= now - MINUTE }
        val changedModel = advanceIfNeeded(now)
        if (changedDay || changedModel) persist()
        publish(now)
    }

    private fun advanceIfNeeded(now: Long): Boolean {
        val before = selectedIndex
        while (selectedIndex < GeminiTtsModel.entries.lastIndex) {
            val model = GeminiTtsModel.entries[selectedIndex]
            val counter = counters.getValue(model)
            val why = when {
                now < counter.blockedUntil -> "서버 사용 한도 도달"
                counter.daily >= EARLY_DAILY -> "하루 사용량에 근접"
                counter.times.size >= EARLY_MINUTE -> "분당 사용량에 근접"
                else -> break
            }
            reason = "${model.label} $why"
            selectedIndex++
        }
        return before != selectedIndex
    }

    /** Reserve durably immediately before a network attempt, never for a cache hit or queued work. */
    @Synchronized fun acquire(): Ticket? {
        val now = clock()
        refreshAt(now)
        val model = mutable.value.model
        val counter = counters.getValue(model)
        if (counter.daily >= DAILY_LIMIT || counter.times.size >= MINUTE_LIMIT || now < counter.blockedUntil) return null
        counter.daily++
        counter.times += now
        // Keep headroom on the first two models. Once advanced, stay there until the quota day resets.
        advanceIfNeeded(now)
        val saved = persist() // On storage failure the caller falls back without making the network request.
        publish(now)
        return if (saved) Ticket(model, day, now) else null
    }

    @Synchronized fun rejected(ticket: Ticket, daily: Boolean, retryMillis: Long) {
        val now = clock()
        refreshAt(now)
        if (ticket.day != day) return // A late response from yesterday cannot disable today's model.
        val counter = counters.getValue(ticket.model)
        counter.blockedUntil = maxOf(counter.blockedUntil, if (daily) nextReset(now) else now + retryMillis.coerceIn(MINUTE, 3_600_000))
        if (ticket.model.ordinal >= selectedIndex) {
            reason = "${ticket.model.label} 서버 사용 한도 도달"
            selectedIndex = minOf(GeminiTtsModel.entries.lastIndex, ticket.model.ordinal + 1)
        }
        persist()
        publish(now)
    }

    private fun publish(now: Long) {
        fun used(model: GeminiTtsModel) = counters.getValue(model).let { GeminiModelUsage(it.times.size, it.daily) }
        mutable.value = GeminiUsageState(GeminiTtsModel.entries[selectedIndex],
            used(GeminiTtsModel.PRIMARY), used(GeminiTtsModel.FALLBACK), used(GeminiTtsModel.FLASH), nextReset(now), reason,
            if (storageReady) null else "사용량을 저장하지 못해 새 음성 생성을 중지했습니다. 저장 공간을 확인해 주세요.")
    }

    private fun persist(): Boolean {
        val root = JSONObject().put("day", day).put("reason", reason.orEmpty()).put("selectedIndex", selectedIndex)
        for ((model, counter) in counters) root.put(model.name, JSONObject().put("daily", counter.daily)
            .put("times", JSONArray(counter.times)).put("blockedUntil", counter.blockedUntil))
        storageReady = runCatching { prefs?.edit()?.putString("usage", root.toString())?.commit() ?: true }.getOrDefault(false)
        return storageReady
    }

    companion object {
        const val MINUTE_LIMIT = 3
        const val DAILY_LIMIT = 10
        private const val EARLY_MINUTE = 2
        private const val EARLY_DAILY = 8
        private const val MINUTE = 60_000L
        private val PACIFIC = ZoneId.of("America/Los_Angeles")
        internal fun date(now: Long): String = Instant.ofEpochMilli(now).atZone(PACIFIC).toLocalDate().toString()
        internal fun nextReset(now: Long): Long = Instant.ofEpochMilli(now).atZone(PACIFIC).toLocalDate().plusDays(1)
            .atStartOfDay(PACIFIC).toInstant().toEpochMilli()
    }
}
