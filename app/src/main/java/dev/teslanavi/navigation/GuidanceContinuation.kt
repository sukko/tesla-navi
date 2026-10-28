package dev.teslanavi.navigation

import dev.teslanavi.drive.DrivePhase
import dev.teslanavi.drive.DriveState
import dev.teslanavi.protocol.NavigationDestination
import kotlin.math.hypot

/** In-memory only: never start a trip from a destination left over from an earlier service session. */
internal class GuidanceContinuation {
    private var vin: String? = null
    private var destination: NavigationDestination.Active? = null
    private var eligible = false
    private var lastMotion: Long? = null
    private var anchor: NavigationPosition? = null
    private var lastFixAt: Long? = null
    private val walking = WalkingDeparture()
    var endReason: ContinuationEndReason? = null
        private set
    val endMessage get() = if (endReason == ContinuationEndReason.WALKING)
        "보행이 지속되어 하차로 추정하고 안내를 종료했습니다" else "차량 연결 끊김 후 10분간 이동이 확인되지 않아 안내를 종료했습니다"
    var usingPhone = false
        private set
    var expired = false
        private set

    fun update(drive: DriveState, moving: Boolean, now: Long): NavigationDestination.Active? {
        if (vin != drive.vin || drive.phase == DrivePhase.IDLE) {
            vin = drive.vin; destination = null; eligible = false; lastMotion = null
            anchor = null; lastFixAt = null; usingPhone = false; expired = false
            walking.reset(); endReason = null
        }
        if (drive.phase == DrivePhase.CONNECTED && drive.snapshot != null) {
            usingPhone = false; expired = false; lastMotion = null
            walking.reset(); endReason = null
            destination = (drive.snapshot.destination as? NavigationDestination.Active)?.takeIf { it.point != null }
            eligible = moving && destination != null && drive.snapshot.gear != dev.teslanavi.protocol.Gear.PARK
            return null
        }
        if (!eligible || expired) { usingPhone = false; return null }
        if (!usingPhone) { usingPhone = true; lastMotion = now; walking.reset() }
        if (now - checkNotNull(lastMotion) >= IDLE_TIMEOUT_MS) {
            expired = true; usingPhone = false; eligible = false
            endReason = ContinuationEndReason.NO_MOVEMENT
            return null
        }
        return destination
    }

    fun step(at: Long, now: Long) { if (usingPhone) walking.step(at, now) }

    /** Only fresh, trusted GPS observations count. A fixed location or small GPS drift cannot extend the timer. */
    fun observe(frame: NavigationEvent.Frame, now: Long) {
        if (frame.source != PositionSource.PHONE) return
        val fixAt = frame.locationAtMillis ?: return
        if (now - fixAt !in 0..10_000 || lastFixAt?.let { fixAt <= it } == true) return
        lastFixAt = fixAt
        if (usingPhone && walking.observe(frame)) {
            expired = true; usingPhone = false; eligible = false; endReason = ContinuationEndReason.WALKING
            return
        }
        val position = frame.position?.takeIf { it.xMeters.isFinite() && it.yMeters.isFinite() }
        val previous = anchor
        val displaced = position != null && previous != null &&
            hypot(position.xMeters - previous.xMeters, position.yMeters - previous.yMeters) >= 30
        val moving = frame.speedKph?.let { it >= 3 } == true || displaced
        if (position != null && (previous == null || moving)) anchor = position
        if (usingPhone && moving) lastMotion = maxOf(checkNotNull(lastMotion), fixAt)
    }

    companion object { const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L }
}
