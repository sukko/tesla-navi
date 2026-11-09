package dev.teslanavi.navigation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiVoiceTest {
    @get:Rule val temp = TemporaryFolder()
    private val pcm = Pcm(ByteArray(4800) { if (it % 2 == 0) 10 else 0 }, 24_000)
    private fun render(voice: GeminiVoice, text: String): Pcm? = CompletableFuture<Pcm?>().also { result ->
        voice.render(text) { result.complete(it) }
    }.get(5, TimeUnit.SECONDS)

    @Test fun repeatedAndPrefetchedGuidanceUsesPersistentCacheWithoutFurtherRequests() = runBlocking {
        val calls = AtomicInteger()
        val dir = temp.newFolder()
        val voice = GeminiVoice({ "key" }, dir, { _, text, _, _ ->
            assertEquals("삼백 미터 앞", text); calls.incrementAndGet(); pcm
        })
        assertNull(voice.load())
        voice.prefetch("300미터 앞")
        assertNotNull(render(voice, "300미터 앞"))
        assertNotNull(render(voice, "삼백 미터 앞"))
        assertEquals(1, calls.get())
        val reloaded = GeminiVoice({ "key" }, dir, { _, _, _, _ -> error("cache should be used") })
        assertNotNull(render(reloaded, "300미터 앞"))
        assertEquals(1, calls.get())
        val missing = GeminiVoice({ "" }, dir)
        assertNotNull(missing.load())
        assertNull(render(missing, "300미터 앞"))
    }

    private fun awaitFallback(voice: GeminiVoice): Pcm? {
        repeat(250) { voice.fallback()?.let { return it }; Thread.sleep(20) }
        return null
    }

    @Test fun storedPhraseIsMadeOnceAndSurvivesTheLossOfThePhraseCache() {
        val calls = mutableListOf<String>()
        val cache = temp.newFolder()
        val saved = temp.newFolder()
        val voice = GeminiVoice({ "key" }, cache, { _, text, _, _ -> synchronized(calls) { calls += text }; pcm }, fallbackDir = saved)
        assertNull(voice.fallback())
        voice.prepareFallback()
        assertNotNull(awaitFallback(voice))
        assertEquals(listOf(STORED_CAMERA_PHRASE), synchronized(calls) { calls.toList() })
        cache.deleteRecursively()
        val restarted = GeminiVoice({ "key" }, cache, { _, _, _, _ -> error("the saved phrase must not be requested again") }, fallbackDir = saved)
        restarted.prepareFallback()
        assertNotNull("works with Gemini failing", restarted.fallback())
    }

    @Test fun anotherVoiceRecordingIsUsedWhenTheCurrentVoiceHasNone() {
        val saved = temp.newFolder()
        val first = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _, _ -> pcm }, speaker = { GeminiSpeaker.Kore }, fallbackDir = saved)
        first.prepareFallback()
        assertNotNull(awaitFallback(first))
        val other = GeminiVoice({ "" }, temp.newFolder(), speaker = { GeminiSpeaker.Zephyr }, fallbackDir = saved)
        other.prepareFallback()
        assertNotNull(other.fallback())
    }

    @Test fun unreachableServiceLeavesNoStoredPhraseAndCanRetry() {
        var fail = true
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _, _ -> if (fail) throw java.io.IOException("offline") else pcm }, clock = { 0L }, fallbackDir = temp.newFolder())
        voice.prepareFallback()
        Thread.sleep(200)
        assertNull(voice.fallback())
        fail = false
        voice.reset() // Clears the failure backoff, as the next retry window would.
        voice.prepareFallback()
        assertNotNull(awaitFallback(voice))
    }

    @Test fun changingVoiceNeverReusesAnotherVoiceAudioAndReturningReusesItsCache() {
        val calls = mutableListOf<GeminiSpeaker>()
        var selected = GeminiSpeaker.Kore
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, _, speaker, _ ->
            synchronized(calls) { calls += speaker }; pcm
        }, speaker = { selected })
        assertNotNull(render(voice, "카메라 안내"))
        voice.reset(); selected = GeminiSpeaker.Zephyr
        assertNotNull(render(voice, "카메라 안내"))
        voice.reset(); selected = GeminiSpeaker.Kore
        assertNotNull(render(voice, "카메라 안내"))
        assertEquals(listOf(GeminiSpeaker.Kore, GeminiSpeaker.Zephyr), calls)
    }

    @Test fun dueAlertDisplacesQueuedPrefetchWithoutExtraConcurrentRequests() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, text, _, _ ->
            val running = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, running) }
            try {
                calls += text
                if (text == "준비 중") { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) }
                pcm
            } finally { active.decrementAndGet() }
        })
        // Observe queue admission only to coordinate threads; assertions cover the externally run work.
        val lock = org.robolectric.util.ReflectionHelpers.getField<Any>(voice, "lock")
        val queued = org.robolectric.util.ReflectionHelpers.getField<Set<String>>(voice, "speculative")
        fun awaitQueue(size: Int) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (synchronized(lock) { queued.size } != size && System.nanoTime() < until) Thread.sleep(2)
            assertEquals(size, synchronized(lock) { queued.size })
        }
        try {
            voice.prefetch("준비 중")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            voice.prefetch("나중 안내")
            awaitQueue(1)
            val result = CompletableFuture<Pcm?>()
            voice.render("지금 안내") { result.complete(it) }
            awaitQueue(0)
            finish.countDown()
            assertNotNull(result.get(3, TimeUnit.SECONDS))
            assertEquals(listOf("준비 중", "지금 안내"), calls.toList())
            assertEquals(1, maximum.get())
        } finally { finish.countDown(); voice.reset() }
    }

    @Test fun requestTimeoutAllowsFallbackAndLaterCompletedAudioIsCached() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _, _ ->
            entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)); pcm
        }, waitMillis = 50)
        val first = CompletableFuture<Pcm?>()
        voice.render("안내") { first.complete(it) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertNull(first.get(1, TimeUnit.SECONDS))
        finish.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (voice.status.value != null && System.nanoTime() < until) Thread.sleep(10)
        assertNotNull(render(voice, "안내"))
    }

    @Test fun cachePlaybackAndVoiceResetNeverSpendQuotaAndNextDayRestoresPrimary() {
        var wall = java.time.Instant.parse("2026-11-03T01:00:00Z").toEpochMilli()
        val ledger = GeminiTtsUsage(clock = { wall })
        val calls = java.util.Collections.synchronizedList(mutableListOf<GeminiTtsModel>())
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _, model -> calls += model; pcm }, usage = ledger)
        for (i in 1..9) assertNotNull(render(voice, "안내 $i"))
        assertEquals(listOf(GeminiTtsModel.PRIMARY, GeminiTtsModel.PRIMARY, GeminiTtsModel.FALLBACK,
            GeminiTtsModel.FALLBACK, GeminiTtsModel.FLASH, GeminiTtsModel.FLASH,
            GeminiTtsModel.FLASH25, GeminiTtsModel.FLASH25, GeminiTtsModel.FLASH25), calls.toList())
        assertNull(render(voice, "새 안내"))
        voice.reset()
        assertNotNull(render(voice, "안내 1")) // A cached earlier model still works after switching and quota exhaustion.
        assertEquals(9, calls.size)
        assertNull(render(voice, "새 안내"))
        wall = ledger.state.value.resetsAt
        assertNotNull(render(voice, "다음 날 안내"))
        assertEquals(GeminiTtsModel.PRIMARY, calls.last())
        assertEquals(1, ledger.state.value.primary.rpd)
        assertEquals(0, ledger.state.value.fallback.rpd)
        assertEquals(0, ledger.state.value.flash.rpd)
        assertEquals(0, ledger.state.value.flash25.rpd)
    }

    @Test fun serverQuotaSwitchesWithinTheSameRequestAndCachesSuccessfulReplacement() {
        val calls = java.util.Collections.synchronizedList(mutableListOf<GeminiTtsModel>())
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _, model ->
            calls += model
            if (model != GeminiTtsModel.FLASH25) throw GeminiTtsFailure(429, "사용 한도", dailyQuota = true)
            pcm
        })
        assertNotNull(render(voice, "카메라 안내"))
        assertEquals(GeminiTtsModel.entries.toList(), calls.toList())
        assertNotNull(render(voice, "카메라 안내"))
        assertEquals(4, calls.size)
        assertNull(voice.status.value)
        assertEquals(GeminiTtsModel.FLASH25, voice.usage.state.value.model)
    }

    @Test fun quotaFailureRotatesModelsAndResetKeepsAccountingWhileDiscardingLateResponses() {
        val calls = AtomicInteger()
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _, _ ->
            calls.incrementAndGet(); throw GeminiTtsFailure(429, "사용 한도")
        })
        assertNull(render(voice, "안내"))
        assertEquals("사용 한도", voice.status.value)
        assertNull(render(voice, "다른 안내"))
        assertEquals(4, calls.get())
        voice.reset()
        assertNull(render(voice, "안내"))
        assertEquals(4, calls.get())
        assertEquals(1, voice.usage.state.value.primary.rpd)
        assertEquals(1, voice.usage.state.value.fallback.rpd)
        assertEquals(1, voice.usage.state.value.flash.rpd)
        assertEquals(1, voice.usage.state.value.flash25.rpd)
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val delayed = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _, _ -> entered.countDown(); gate.await(); pcm })
        val result = CompletableFuture<Pcm?>()
        delayed.render("안내") { result.complete(it) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        delayed.reset(); gate.countDown()
        assertNull(result.get(2, TimeUnit.SECONDS))
    }
}
