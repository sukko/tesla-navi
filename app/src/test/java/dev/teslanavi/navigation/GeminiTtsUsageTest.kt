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

    @Test fun minuteHeadroomCascadesThroughAllThreeAndLastModelWaitsForRollingMinute() {
        val usage = GeminiTtsUsage(prefs) { now }
        val models = (1..7).map { checkNotNull(usage.acquire()).model }
        assertEquals(listOf(GeminiTtsModel.PRIMARY, GeminiTtsModel.PRIMARY,
            GeminiTtsModel.FALLBACK, GeminiTtsModel.FALLBACK,
            GeminiTtsModel.FLASH, GeminiTtsModel.FLASH, GeminiTtsModel.FLASH), models)
        assertNull(usage.acquire())
        now += 59_999
        assertNull(usage.acquire())
        now++
        assertEquals(GeminiTtsModel.FLASH, usage.acquire()?.model)
        assertEquals(1, usage.state.value.flash.rpm)
        assertEquals(4, usage.state.value.flash.rpd)
    }

    @Test fun dailyHeadroomAndFinalDailyLimitPersistAcrossRestarts() {
        val usage = GeminiTtsUsage(prefs) { now }
        for (model in GeminiTtsModel.entries) {
            val allowed = if (model == GeminiTtsModel.FLASH) 10 else 8
            repeat(allowed) {
                assertEquals(model, usage.acquire()?.model)
                now += 60_001 // Exercise RPD independently of RPM.
            }
        }
        assertNull(usage.acquire())
        val reloaded = GeminiTtsUsage(prefs) { now }
        assertEquals(GeminiTtsModel.FLASH, reloaded.state.value.model)
        assertEquals(8, reloaded.state.value.primary.rpd)
        assertEquals(8, reloaded.state.value.fallback.rpd)
        assertEquals(10, reloaded.state.value.flash.rpd)
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
        val primary = checkNotNull(usage.acquire())
        usage.rejected(primary, daily = true, retryMillis = 1000)
        assertEquals(GeminiTtsModel.FALLBACK, GeminiTtsUsage(prefs) { now }.state.value.model)
        val second = checkNotNull(usage.acquire())
        usage.rejected(second, daily = true, retryMillis = 1000)
        val third = checkNotNull(usage.acquire())
        usage.rejected(third, daily = true, retryMillis = 1000)
        now += 120_000
        assertNull(GeminiTtsUsage(prefs) { now }.acquire())
        now = usage.state.value.resetsAt
        usage.rejected(primary, daily = true, retryMillis = 1000)
        assertEquals(GeminiTtsModel.PRIMARY, usage.acquire()?.model)
    }

    @Test fun concurrentAdmissionsCannotExceedAnyModelLimit() {
        val usage = GeminiTtsUsage(clock = { now })
        val pool = Executors.newFixedThreadPool(4)
        try {
            val admitted = pool.invokeAll((1..20).map { Callable { usage.acquire() } }).mapNotNull { it.get() }
            assertEquals(7, admitted.size)
            assertEquals(mapOf(GeminiTtsModel.PRIMARY to 2, GeminiTtsModel.FALLBACK to 2, GeminiTtsModel.FLASH to 3),
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
}
