package dev.teslanavi.navigation

import kotlin.math.hypot

/** A heuristic, not a definitive exit signal. GPS walking speed alone (e.g. slow traffic) never stops guidance. */
internal class WalkingDeparture {
    private var since: Long? = null
    private var lastFix: Long? = null
    private var origin: NavigationPosition? = null
    private val steps = ArrayDeque<Long>()

    fun reset() { since = null; lastFix = null; origin = null; steps.clear() }

    fun step(at: Long, now: Long) {
        if (now - at !in 0..5000 || steps.lastOrNull()?.let { at - it < 200 } == true) return
        steps.addLast(at)
        while (steps.size > 400 || steps.firstOrNull()?.let { now - it > 150_000 } == true) steps.removeFirst()
    }

    fun observe(frame: NavigationEvent.Frame): Boolean {
        val at = frame.locationAtMillis ?: return false
        val position = frame.position?.takeIf { it.xMeters.isFinite() && it.yMeters.isFinite() }
        if (frame.speedKph !in 1..8 || position == null) { reset(); return false }
        if (lastFix?.let { at - it > 15_000 } == true) reset()
        if (since == null) { since = at; origin = position }
        lastFix = at
        while (steps.firstOrNull()?.let { it < checkNotNull(since) } == true) steps.removeFirst()
        val first = steps.firstOrNull() ?: return false
        val last = steps.last()
        val start = checkNotNull(origin)
        return at - checkNotNull(since) >= 120_000 && steps.size >= 40 &&
            at - last in 0..10_000 && last - first >= 60_000 &&
            hypot(position.xMeters - start.xMeters, position.yMeters - start.yMeters) >= 50
    }
}
