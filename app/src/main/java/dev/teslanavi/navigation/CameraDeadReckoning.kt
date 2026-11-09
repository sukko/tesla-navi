package dev.teslanavi.navigation

import kotlin.math.roundToInt

/**
 * Carries cameras already seen ahead forward by the car's speed. On 2026-11-09 two cameras seen 0.4–1.6 km ahead were
 * never announced because no fresh position or camera list reached the alert distance. A real reroute that leaves
 * the camera off the new path can still cause one announcement for it; passing a camera unannounced is worse.
 */
internal class CameraDeadReckoning {
    data class Passed(val reading: CameraReading, val lastMeters: Int, val ageMillis: Long, val source: PositionSource)

    private class Tracked(val reading: CameraReading, val baseMeters: Double, val baseProgress: Double,
        val refreshedAt: Long, val source: PositionSource)

    private val tracked = linkedMapOf<String, Tracked>()
    private var progress = 0.0
    private var advancedAt: Long? = null

    /** Integrates travel. A gap longer than a few seconds is capped: speed over it is unknown. */
    fun advance(now: Long, speedKph: Double?) {
        val last = advancedAt
        advancedAt = now
        if (last == null || speedKph == null || !speedKph.isFinite() || speedKph < 0) return
        progress += speedKph / 3.6 * (now - last).coerceIn(0, MAX_STEP_MILLIS) / 1000.0
    }

    private fun remaining(item: Tracked) = item.baseMeters - (progress - item.baseProgress)

    /** A fresh frame. Cameras that left it close to their position are taken as passed and returned. */
    fun observe(cameras: List<CameraReading>, now: Long, source: PositionSource): List<Passed> {
        for (camera in cameras) if (camera.distanceMeters in 0..TRACK_METERS)
            tracked[camera.id] = Tracked(camera, camera.distanceMeters.toDouble(), progress, now, source)
        val present = cameras.mapTo(HashSet()) { it.id }
        return drop(now) { it.reading.id !in present && remaining(it) <= PASSED_METERS }
    }

    /** Cameras not refreshed since [before], at their estimated distance. */
    fun estimated(before: Long): List<CameraReading> = tracked.values.filter { it.refreshedAt < before }.mapNotNull { item ->
        remaining(item).roundToInt().takeIf { it in 0..TRACK_METERS }?.let { item.reading.copy(distanceMeters = it) }
    }.sortedBy { it.distanceMeters }

    /** Estimated past the camera, or too long without any confirmation. */
    fun expire(now: Long): List<Passed> = drop(now) { remaining(it) < -PASSED_METERS || now - it.refreshedAt > MAX_AGE_MILLIS }

    fun clear() { tracked.clear(); advancedAt = null }

    private fun drop(now: Long, condition: (Tracked) -> Boolean): List<Passed> {
        val gone = tracked.values.filter(condition)
        gone.forEach { tracked.remove(it.reading.id) }
        return gone.map { Passed(it.reading, remaining(it).roundToInt(), now - it.refreshedAt, it.source) }
    }

    companion object {
        /** Beyond the furthest alert setting plus the preparation distance. */
        const val TRACK_METERS = 2000
        const val PASSED_METERS = 150
        const val MAX_AGE_MILLIS = 180_000L
        private const val MAX_STEP_MILLIS = 5_000L
    }
}
