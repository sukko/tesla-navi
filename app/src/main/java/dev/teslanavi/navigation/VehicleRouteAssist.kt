package dev.teslanavi.navigation

import dev.teslanavi.protocol.GeoPoint
import dev.teslanavi.protocol.Gear
import dev.teslanavi.protocol.VehicleLocation
import kotlin.math.*

data class VehicleGuidanceFix(val location: VehicleLocation, val speedKph: Double?, val gear: Gear,
    val receivedAtMillis: Long, val wallTimeMillis: Long)

/** Receipt of another BLE packet does not make a frozen vehicle position fresh. */
internal class VehicleFixTracker {
    private var fix: VehicleGuidanceFix? = null
    private var lastPoint: GeoPoint? = null
    private var lastReceived: Long? = null
    private var changedAt: Long? = null
    private var usable = false

    fun update(value: VehicleGuidanceFix?) {
        if (value == null) {
            fix = null; lastPoint = null; lastReceived = null; changedAt = null; usable = false
            return
        }
        if (value == fix) return
        val point = value.location.point
        val previous = lastPoint
        val elapsed = lastReceived?.let { value.receivedAtMillis - it }
        val distance = if (point != null && previous != null) RouteGeometry.distance(previous, point) else null
        val changed = distance != null && distance >= 2.0
        val plausible = elapsed == null || (elapsed in 1..10_000 && distance != null && distance <= 40 + elapsed * 0.065)
        val gpsFresh = value.location.gpsAsOf?.takeIf { it in 1..Long.MAX_VALUE / 1000 }
            ?.let { abs(value.wallTimeMillis - it * 1000) <= 10_000 } == true
        val stateFresh = value.location.timestampMillis?.let { abs(value.wallTimeMillis - it) <= 10_000 } == true
        // The raw GPS clock can stop in a tunnel while the car's estimated coordinates continue.
        val estimatedFresh = value.location.estimatedValid == true && (stateFresh || (changed && plausible))
        usable = point != null && value.location.estimatedValid != false &&
            value.gear != Gear.REVERSE && value.gear != Gear.PARK &&
            value.speedKph?.let { it.isFinite() && it in 0.0..250.0 } == true &&
            (gpsFresh || estimatedFresh) && plausible
        if (changed || previous == null) changedAt = value.receivedAtMillis
        lastPoint = point; lastReceived = value.receivedAtMillis; fix = value
    }

    fun current(now: Long): VehicleGuidanceFix? = fix?.takeIf {
        usable && now - it.receivedAtMillis in 0..10_000 &&
            (it.speedKph!! < 3 || changedAt?.let { changed -> now - changed in 0..10_000 } == true)
    }
}

/** Local metre projection of the documented WGS84 route polyline; distances follow its bends. */
internal class RouteGeometry(points: List<GeoPoint>) {
    private val origin = points.first()
    private val longitudeScale = 111_195.0 * cos(Math.toRadians(origin.latitude))
    private val vertices = points.map(::xy)
    private val lengths = DoubleArray(points.size)
    val totalMeters: Double get() = lengths.last()

    init {
        require(points.size in 2..200_000)
        for (i in 1 until vertices.size) lengths[i] = lengths[i - 1] + hypot(
            vertices[i].xMeters - vertices[i - 1].xMeters, vertices[i].yMeters - vertices[i - 1].yMeters)
        require(totalMeters > 0 && totalMeters.isFinite())
    }

    data class Match(val meters: Double, val offsetMeters: Double)
    private fun xy(p: GeoPoint) = NavigationPosition((p.longitude - origin.longitude) * longitudeScale,
        (p.latitude - origin.latitude) * 111_195.0)

    fun match(point: GeoPoint, range: ClosedFloatingPointRange<Double> = 0.0..totalMeters,
        heading: Int? = null, maxOffset: Double = 25.0): Match? {
        val p = xy(point)
        val candidates = mutableListOf<Match>()
        var first = lengths.binarySearch(range.start).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        first = first.coerceAtMost(vertices.lastIndex - 1)
        for (i in first until vertices.lastIndex) {
            if (lengths[i] > range.endInclusive) break
            val a = vertices[i]; val b = vertices[i + 1]
            val dx = b.xMeters - a.xMeters; val dy = b.yMeters - a.yMeters
            val squared = dx * dx + dy * dy
            if (squared < 0.01) continue
            if (heading != null) {
                val bearing = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
                if (abs((bearing - heading + 540) % 360 - 180) > 60) continue
            }
            val t = (((p.xMeters - a.xMeters) * dx + (p.yMeters - a.yMeters) * dy) / squared).coerceIn(0.0, 1.0)
            val progress = lengths[i] + sqrt(squared) * t
            if (progress !in range) continue
            val offset = hypot(p.xMeters - a.xMeters - t * dx, p.yMeters - a.yMeters - t * dy)
            if (offset <= maxOffset) candidates += Match(progress, offset)
        }
        val best = candidates.minByOrNull { it.offsetMeters } ?: return null
        // Overpasses, parallel carriageways and self-crossings must not pick an arbitrary branch.
        if (candidates.any { abs(it.meters - best.meters) > 60 && it.offsetMeters <= best.offsetMeters + 10 }) return null
        return best
    }

    companion object {
        fun distance(a: GeoPoint, b: GeoPoint): Double = hypot(
            (b.longitude - a.longitude) * 111_195 * cos(Math.toRadians((a.latitude + b.latitude) / 2)),
            (b.latitude - a.latitude) * 111_195)
    }
}

internal data class RouteCamera(val reading: CameraReading, val meters: Double)

/** Needs a confirmed phone/route anchor first; cannot invent a route when both sources are absent. */
internal class VehicleRouteAssist(val geometry: RouteGeometry, val cameras: List<RouteCamera>) {
    private var progress: RouteGeometry.Match? = null
    private var progressAt: Long? = null
    private var lastFixAt: Long? = null

    fun anchor(point: GeoPoint, expectedMeters: Double, now: Long) {
        val match = geometry.match(point, (expectedMeters - 50).coerceAtLeast(0.0)..
            (expectedMeters + 50).coerceAtMost(geometry.totalMeters)) ?: return
        progress = match; progressAt = now; lastFixAt = null
    }

    fun locate(fix: VehicleGuidanceFix, now: Long): RouteGeometry.Match? {
        val previous = progress ?: return null
        val elapsed = progressAt?.let { now - it }?.takeIf { it in 0..30_000 } ?: return null
        if (lastFixAt == fix.receivedAtMillis) return previous
        val match = geometry.match(fix.location.point ?: return null,
            (previous.meters - 20).coerceAtLeast(0.0)..
                (previous.meters + 40 + elapsed * 0.065).coerceAtMost(geometry.totalMeters), fix.location.headingDegrees) ?: return null
        progress = match; progressAt = fix.receivedAtMillis; lastFixAt = fix.receivedAtMillis
        return match
    }

    fun camerasAhead(meters: Double) = cameras.mapNotNull {
        val distance = it.meters - meters
        if (distance in 0.0..10_000.0) it.reading.copy(distanceMeters = distance.roundToInt()) else null
    }.sortedBy { it.distanceMeters }
}
