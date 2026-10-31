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
        val voice = GeminiVoice({ "key" }, dir, { _, text, _ ->
            assertEquals("삼백 미터 앞", text); calls.incrementAndGet(); pcm
        })
        assertNull(voice.load())
        voice.prefetch("300미터 앞")
        assertNotNull(render(voice, "300미터 앞"))
        assertNotNull(render(voice, "삼백 미터 앞"))
        assertEquals(1, calls.get())
        val reloaded = GeminiVoice({ "key" }, dir, { _, _, _ -> error("cache should be used") })
        assertNotNull(render(reloaded, "300미터 앞"))
        assertEquals(1, calls.get())
        val missing = GeminiVoice({ "" }, dir)
        assertNotNull(missing.load())
        assertNull(render(missing, "300미터 앞"))
    }

    @Test fun changingVoiceNeverReusesAnotherVoiceAudioAndReturningReusesItsCache() {
        val calls = mutableListOf<GeminiSpeaker>()
        var selected = GeminiSpeaker.Kore
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, _, speaker ->
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
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, text, _ ->
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
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _ ->
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

    @Test fun quotaFailureHasCooldownAndChangingKeyDiscardsLateResponses() {
        val calls = AtomicInteger()
        val voice = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _ ->
            calls.incrementAndGet(); throw GeminiTtsFailure(429, "사용 한도")
        })
        assertNull(render(voice, "안내"))
        assertEquals("사용 한도", voice.status.value)
        assertNull(render(voice, "다른 안내"))
        assertEquals(1, calls.get())
        voice.reset()
        assertNull(render(voice, "안내"))
        assertEquals(2, calls.get())
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val delayed = GeminiVoice({ "key" }, temp.newFolder(), { _, _, _ -> entered.countDown(); gate.await(); pcm })
        val result = CompletableFuture<Pcm?>()
        delayed.render("안내") { result.complete(it) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        delayed.reset(); gate.countDown()
        assertNull(result.get(2, TimeUnit.SECONDS))
    }
}
