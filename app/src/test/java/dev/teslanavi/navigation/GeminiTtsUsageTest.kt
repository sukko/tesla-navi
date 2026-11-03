package dev.teslanavi.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiTtsUsageTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("quota-test", Context.MODE_PRIVATE)
    private var now = Instant.parse("2026-11-03T01:00:00Z").toEpochMilli()

    @Test fun minuteHeadroomCascadesThroughAllFourAndLastModelWaitsForRollingMinute() {
        val usage = GeminiTtsUsage(prefs) { now }
        val models = (1..9).map { checkNotNull(usage.acquire()).model }
        assertEquals(listOf(GeminiTtsModel.PRIMARY, GeminiTtsModel.PRIMARY,
            GeminiTtsModel.FALLBACK, GeminiTtsModel.FALLBACK,
            GeminiTtsModel.FLASH, GeminiTtsModel.FLASH,
            GeminiTtsModel.FLASH25, GeminiTtsModel.FLASH25, GeminiTtsModel.FLASH25), models)
        assertNull(usage.acquire())
        now += 59_999
        assertNull(usage.acquire())
        now++
        assertEquals(GeminiTtsModel.FLASH25, usage.acquire()?.model)
        assertEquals(1, usage.state.value.flash25.rpm)
        assertEquals(4, usage.state.value.flash25.rpd)
    }

    @Test fun dailyHeadroomAndFinalDailyLimitPersistAcrossRestarts() {
        var usage = GeminiTtsUsage(prefs) { now }
        for (model in GeminiTtsModel.entries) {
            val allowed = if (model == GeminiTtsModel.FLASH25) 10 else 9
            repeat(allowed) { index ->
                assertEquals(model, usage.acquire()?.model)
                now += 60_001 // Exercise RPD independently of RPM.
                if (index == 7 && model != GeminiTtsModel.FLASH25) {
                    usage = GeminiTtsUsage(prefs) { now }
                    assertEquals("Eight requests must still allow the ninth after restart", model, usage.state.value.model)
                }
            }
        }
        assertNull(usage.acquire())
        val reloaded = GeminiTtsUsage(prefs) { now }
        assertEquals(GeminiTtsModel.FLASH25, reloaded.state.value.model)
        assertEquals(9, reloaded.state.value.primary.rpd)
        assertEquals(9, reloaded.state.value.fallback.rpd)
        assertEquals(9, reloaded.state.value.flash.rpd)
        assertEquals(10, reloaded.state.value.flash25.rpd)
        assertNull(reloaded.acquire())
    }

    @Test fun persistedMinuteWindowAndSelectedModelDoNotResetWhenAppRestarts() {
        GeminiTtsUsage(prefs) { now }.apply { acquire(); acquire() }
        val restored = GeminiTtsUsage(prefs) { now }
        assertEquals(2, restored.state.value.primary.rpm)
        assertEquals(GeminiTtsModel.FALLBACK, restored.state.value.model)
        now += 60_000
        restored.refresh()
        assertEquals(0, restored.state.value.primary.rpm)
        assertEquals(GeminiTtsModel.FALLBACK, restored.acquire()?.model)
    }

    @Test fun koreanMidnightDoesNotResetButPacificMidnightRestoresFirstModel() {
        now = Instant.parse("2026-11-02T14:58:00Z").toEpochMilli()
        val usage = GeminiTtsUsage(prefs) { now }.apply { acquire(); acquire() }
        now = Instant.parse("2026-11-02T15:00:01Z").toEpochMilli() // Korean midnight, still the same provider day.
        usage.refresh()
        assertEquals(2, usage.state.value.primary.rpd)
        assertEquals(GeminiTtsModel.FALLBACK, usage.state.value.model)
        now = Instant.parse("2026-11-03T08:00:00Z").toEpochMilli()
        usage.refresh()
        assertEquals(0, usage.state.value.primary.rpd)
        assertEquals(0, usage.state.value.fallback.rpd)
        assertEquals(GeminiTtsModel.PRIMARY, usage.acquire()?.model)
        assertEquals(GeminiTtsModel.PRIMARY, GeminiTtsUsage(prefs) { now }.state.value.model)
    }

    @Test fun resetFollowsPacificDaylightSavingAndMinuteWindowSurvivesMidnight() {
        fun resetHours(instant: String) = Instant.ofEpochMilli(GeminiTtsUsage.nextReset(Instant.parse(instant).toEpochMilli()))
            .atZone(ZoneId.of("Asia/Seoul")).hour
        assertEquals(17, resetHours("2026-11-03T01:00:00Z"))
        assertEquals(16, resetHours("2026-07-03T01:00:00Z"))
        val spring = Instant.parse("2026-03-08T08:00:00Z").toEpochMilli()
        val autumn = Instant.parse("2026-11-01T07:00:00Z").toEpochMilli()
        assertEquals(23 * 3_600_000L, GeminiTtsUsage.nextReset(spring) - spring)
        assertEquals(25 * 3_600_000L, GeminiTtsUsage.nextReset(autumn) - autumn)
        now = Instant.parse("2026-11-03T07:59:50Z").toEpochMilli()
        val usage = GeminiTtsUsage(prefs) { now }
        usage.acquire()
        now += 10_000; usage.refresh()
        assertEquals(1, usage.state.value.primary.rpm)
        assertEquals(0, usage.state.value.primary.rpd)
    }

    @Test fun serverDailyRejectionPersistsAndLateYesterdayResponseCannotBlockNewDay() {
        val usage = GeminiTtsUsage(prefs) { now }
        var primary: GeminiTtsUsage.Ticket? = null
        for (model in GeminiTtsModel.entries) {
            val ticket = checkNotNull(usage.acquire())
            assertEquals(model, ticket.model)
            if (primary == null) primary = ticket
            usage.rejected(ticket, daily = true, retryMillis = 1000)
        }
        now += 120_000
        assertNull(GeminiTtsUsage(prefs) { now }.acquire())
        now = usage.state.value.resetsAt
        usage.rejected(checkNotNull(primary), daily = true, retryMillis = 1000)
        assertEquals(GeminiTtsModel.PRIMARY, usage.acquire()?.model)
    }

    @Test fun concurrentAdmissionsCannotExceedAnyModelLimit() {
        val usage = GeminiTtsUsage(clock = { now })
        val pool = Executors.newFixedThreadPool(4)
        try {
            val admitted = pool.invokeAll((1..20).map { Callable { usage.acquire() } }).mapNotNull { it.get() }
            assertEquals(9, admitted.size)
            assertEquals(mapOf(GeminiTtsModel.PRIMARY to 2, GeminiTtsModel.FALLBACK to 2,
                GeminiTtsModel.FLASH to 2, GeminiTtsModel.FLASH25 to 3),
                admitted.groupingBy { it.model }.eachCount())
        } finally { pool.shutdownNow() }
    }

    @Test fun storageFailureNeverAdmitsUnrecordedNetworkCalls() {
        val disk = prefs
        val unavailable = object : android.content.SharedPreferences by disk {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = disk.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor = this
                    override fun commit() = false
                }
            }
        }
        val usage = GeminiTtsUsage(unavailable) { now }
        assertNull(usage.acquire())
        assertNotNull(usage.state.value.error)
    }

    @Test fun unreadableLedgerFailsClosedUntilNextQuotaDay() {
        prefs.edit().putString("usage", "not-json").commit()
        val usage = GeminiTtsUsage(prefs) { now }
        assertNull(usage.acquire())
        now = usage.state.value.resetsAt
        assertEquals(GeminiTtsModel.PRIMARY, usage.acquire()?.model)
    }

    @Test fun existingThreeModelLedgerKeepsCountsAndAdvancesToNewLastModel() {
        val saved = org.json.JSONObject().put("day", GeminiTtsUsage.date(now)).put("selectedIndex", 2)
        saved.put("PRIMARY", org.json.JSONObject().put("daily", 9))
        saved.put("FALLBACK", org.json.JSONObject().put("daily", 9))
        saved.put("FLASH", org.json.JSONObject().put("daily", 10))
        prefs.edit().putString("usage", saved.toString()).commit()
        val usage = GeminiTtsUsage(prefs) { now }
        assertEquals(9, usage.state.value.primary.rpd)
        assertEquals(9, usage.state.value.fallback.rpd)
        assertEquals(10, usage.state.value.flash.rpd)
        assertEquals(0, usage.state.value.flash25.rpd)
        assertEquals(GeminiTtsModel.FLASH25, usage.acquire()?.model)
        assertEquals(1, GeminiTtsUsage(prefs) { now }.state.value.flash25.rpd)
        now = usage.state.value.resetsAt
        usage.refresh()
        assertEquals(GeminiTtsModel.PRIMARY, usage.state.value.model)
        assertEquals(0, usage.state.value.flash25.rpd)
    }
}
