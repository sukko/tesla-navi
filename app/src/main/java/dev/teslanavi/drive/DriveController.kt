package dev.teslanavi.drive

import android.content.Context
import dev.teslanavi.TeslaNaviApp
import dev.teslanavi.BuildConfig
import dev.teslanavi.ble.AndroidBleTransport
import dev.teslanavi.data.BatteryStore
import dev.teslanavi.data.VehicleKeyStore
import dev.teslanavi.data.VehicleProfile
import dev.teslanavi.logbook.ChargeDetection
import dev.teslanavi.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

private class EnrollmentApprovalTimeout : IllegalStateException("키 등록 승인을 확인하지 못했습니다. 차량의 키 목록과 키 카드 승인을 확인해 주세요.")
/** VCSEC reported the vehicle asleep, so the infotainment handshake was not attempted. */
internal class VehicleAsleepException : IllegalStateException("차량이 잠들어 있어 연결을 미뤘습니다. 차량 문을 열거나 Tesla 앱으로 깨우면 연결합니다.")

enum class DrivePhase { IDLE, CONNECTING, PAIRING, AUTHENTICATING, CONNECTED, RECONNECTING, ERROR }
data class DriveState(
    val vin: String? = null,
    val phase: DrivePhase = DrivePhase.IDLE,
    val status: String = "차량을 연결해 주세요",
    val snapshot: DriveSnapshot? = null,
    val updatedAt: Long? = null,
    val fingerprint: String? = null,
    val error: String? = null,
    val events: List<String> = emptyList(),
    val automatic: Boolean = false,
    /** The last attempt ended because VCSEC reported the vehicle asleep. */
    val asleep: Boolean = false,
) {
    val active get() = phase !in setOf(DrivePhase.IDLE, DrivePhase.ERROR)
}

class DriveController(private val context: Context, private val keyStore: VehicleKeyStore) {
    private val history = ConnectionHistory(context).apply {
        append("앱 시작 · ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}")
    }
    private val _state = MutableStateFlow(DriveState(events = history.read()))
    val state = _state.asStateFlow()
    // Kept outside DriveState so reconnects, failures and app restarts keep the last reading for the main screen.
    private val batteryStore = BatteryStore(context)
    val battery = batteryStore.readings
    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private val attempts = AttemptLog()

    // Writes through AttemptLog so repeated automatic failures do not crowd out the rest of the history.
    private fun write(lines: List<String>): List<String> {
        var events = history.read()
        lines.forEach { events = history.append(it) }
        return events
    }

    fun prepare(vin: String, automatic: Boolean = false) {
        _state.value = DriveState(vin, DrivePhase.CONNECTING, "연결 준비 중", automatic = automatic,
            events = write(attempts.begin(automatic, if (automatic) "자동 연결 준비" else "수동 연결 준비")))
    }
    fun clear() { attempts.cancelled(); _state.value = DriveState(events = history.read()) }
    fun requestRefresh() { refresh.trySend(Unit) }
    fun recordAutomaticStart(message: String) {
        android.util.Log.i("TeslaNaviConnection", "자동 시작 · $message")
        val events = history.append("자동 시작 · $message")
        _state.update { it.copy(events = events) }
    }
    fun recordGuidance(message: String) {
        android.util.Log.i("TeslaNaviConnection", "카메라 안내 · $message")
        val events = history.append("카메라 안내 · $message")
        _state.update { it.copy(events = events) }
    }
    private fun status(message: String, phase: DrivePhase? = null) {
        android.util.Log.i("TeslaNaviConnection", message)
        val events = write(attempts.line("[${phase ?: _state.value.phase}] $message"))
        _state.update { it.copy(phase = phase ?: it.phase, status = message,
            events = events) }
    }
    /** History-only line; unlike status() it leaves the status text (and notification) alone. */
    private fun note(message: String) {
        android.util.Log.i("TeslaNaviConnection", message)
        val events = write(attempts.line("[${_state.value.phase}] $message"))
        _state.update { it.copy(events = events) }
    }
    internal fun recordBattery(vin: String, battery: BatterySnapshot, receivedAt: Long = System.currentTimeMillis()) =
        batteryStore.save(vin, battery, receivedAt)
    fun fail(error: Throwable) {
        val message = when (error) {
            is TimeoutCancellationException -> "차량 응답 시간이 초과되었습니다. 차량 가까이에서 화면을 켜고 다시 연결해 주세요."
            is SecurityException -> "Bluetooth 권한을 허용한 뒤 다시 시도해 주세요."
            else -> error.message?.take(220) ?: "차량 연결을 완료하지 못했습니다. 다시 시도해 주세요."
        }
        android.util.Log.w("TeslaNaviConnection", "Connection failed: ${error.javaClass.simpleName}: $message")
        val outcome = "실패 단계: ${_state.value.phase} · ${error.javaClass.simpleName} · $message"
        val events = write(attempts.failed(outcome, outcome))
        val asleep = error is VehicleAsleepException
        _state.update { it.copy(phase = DrivePhase.ERROR, status = if (asleep) "차량 잠듦 · 깨어나면 연결" else "연결을 확인해 주세요",
            snapshot = null, updatedAt = null, error = message, events = events, asleep = asleep) }
    }

    suspend fun run(profile: VehicleProfile, enroll: Boolean) {
        // A dropped BLE link reconnects inside the session; only the end of the session closes an open trip.
        try { connect(profile, enroll) } finally { finishTrip(profile.vin) }
    }

    private suspend fun connect(profile: VehicleProfile, enroll: Boolean) {
        status(if (enroll) "앱 키 등록 시작 · 저장된 키 준비 중" else "차량 인증 연결 시작 · 저장된 키 준비 중")
        val keys = withContext(Dispatchers.IO) { keyStore.getOrCreate(profile.vin) }
        _state.update { it.copy(fingerprint = TeslaKeys.fingerprint(keys.public)) }
        var enrolled = !enroll
        var enrollmentSent = false
        var enrollmentDeadline: Long? = null
        var failures = 0
        while (currentCoroutineContext().isActive) {
            var client: TeslaClient? = null
            try {
                val transport = AndroidBleTransport.connect(context, profile.vin) { status(it) }
                client = TeslaClient(profile.vin, keys, transport) { message -> note(message) }
                if (!enrolled) {
                    if (!enrollmentSent) {
                        client.requestKeyEnrollment()
                        enrollmentSent = true
                        enrollmentDeadline = android.os.SystemClock.elapsedRealtime() + 90000
                    }
                    status("차량에서 키 카드를 인식시키고 등록을 승인해 주세요", DrivePhase.PAIRING)
                    val expires = checkNotNull(enrollmentDeadline)
                    while (true) {
                        if (android.os.SystemClock.elapsedRealtime() >= expires) throw EnrollmentApprovalTimeout()
                        try { client.authenticate(); enrolled = true; break }
                        catch (error: Exception) {
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            val expected = error is KeyNotRegisteredException || error is TimeoutCancellationException ||
                                (error is VehicleFault && error.code in setOf(1, 2, 3, 11))
                            if (!expected) throw error
                            if (android.os.SystemClock.elapsedRealtime() >= expires) throw EnrollmentApprovalTimeout()
                            delay(2000)
                        }
                    }
                    status("앱 키 등록 확인 완료")
                } else {
                    // A parked car keeps VCSEC and its advertisement awake while infotainment sleeps; a handshake
                    // would only time out. No answer leaves the old behaviour (handshake) in place.
                    if (client.readVehicleSleep() == VehicleSleep.ASLEEP) throw VehicleAsleepException()
                    status("차량 인증 중", DrivePhase.AUTHENTICATING)
                    client.authenticate()
                }
                _state.update { it.copy(events = write(attempts.connected())) }
                status("목적지 확인 중", DrivePhase.CONNECTED)
                app.autoConnect.verified(profile.vin)
                val reader = DriveStateReader(client::readDriveState, client::authenticate) { status(it) }
                var firstSnapshot = true
                val batteryPoll = BatteryPoll()
                lastChargeNote = null
                val locationProbe = LocationProbe()
                while (currentCoroutineContext().isActive) {
                    val snapshot = reader.next()
                    if (firstSnapshot) {
                        note("주행 정보 수신 · 기어 ${snapshot.gear} · 속도 ${snapshot.speedKph?.toInt() ?: "미제공"} km/h")
                        firstSnapshot = false
                    }
                    val polledAt = android.os.SystemClock.elapsedRealtime()
                    locationProbe.observe(snapshot.location, client.locationRejection, System.currentTimeMillis())?.let(::note)
                    val previous = _state.value.snapshot?.destination
                    _state.update { it.copy(snapshot = snapshot, updatedAt = System.currentTimeMillis(), error = null) }
                    if (previous != snapshot.destination) status(when (snapshot.destination) {
                        NavigationDestination.None -> "차량에 설정된 목적지가 없습니다"
                        is NavigationDestination.Active -> "차량 목적지를 확인했습니다"
                    })
                    failures = 0
                    if (app.features.logbook.value) recordTrip(profile.vin, snapshot, batteryPoll, polledAt)
                    if (batteryPoll.due(polledAt)) readBattery(client, profile.vin, batteryPoll, snapshot.odometerKm)
                    // The 4s battery read fits inside the 5s drive cadence instead of delaying the next drive read.
                    withTimeoutOrNull((polledAt + 5000 - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1)) { refresh.receive() }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (error is KeyNotRegisteredException) app.autoConnect.forgetVerification(profile.vin)
                val terminal = error is KeyNotRegisteredException || error is EnrollmentApprovalTimeout || error is EnrollmentRejectedException || error is SecurityException ||
                    (error is VehicleFault && error.code !in setOf(1, 2, 11)) || error is VehicleAsleepException ||
                    error is IllegalArgumentException || error is java.security.GeneralSecurityException
                // An automatic handshake timeout gets one more try; the proximity scan retries later anyway.
                val automaticHandshake = _state.value.automatic && _state.value.phase == DrivePhase.AUTHENTICATING &&
                    error is TimeoutCancellationException
                val limit = if (automaticHandshake) 1 else 3
                if (terminal || ++failures > limit) throw error
                // Clear stale destination immediately; a new connection establishes a new crypto session.
                _state.update { it.copy(snapshot = null, updatedAt = null) }
                status(error.message?.take(220) ?: "차량 응답 대기 실패")
                status("차량 재연결 중 ($failures/$limit)", DrivePhase.RECONNECTING)
            } finally { client?.close() }
            delay((failures * 2000L).coerceAtMost(6000))
        }
    }

    private val app get() = context.applicationContext as TeslaNaviApp
    private var lastChargeNote: String? = null

    /** Never throws except for cancellation: a missing or failed battery read must not end the drive session. */
    private suspend fun readBattery(client: TeslaClient, vin: String, poll: BatteryPoll, odometerKm: Double?) {
        val line = try {
            val charge = client.readChargeState()
            if (charge != null) recordBattery(vin, charge.battery)
            charge?.batteryLevel?.let { level -> runCatching { app.trips.lateBattery(vin, level, System.currentTimeMillis()) } }
            val now = android.os.SystemClock.elapsedRealtime()
            poll.received(charge?.battery, now).also {
                if (charge != null && app.features.logbook.value) recordCharge(vin, charge, odometerKm, poll, now)
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            currentCoroutineContext().ensureActive()
            poll.failed(error, android.os.SystemClock.elapsedRealtime())
        }
        line?.let { note(it) }
    }

    /** Never throws: the trip log is optional. Parking asks for a battery read right away so the trip gets a fresh end value. */
    private fun recordTrip(vin: String, snapshot: DriveSnapshot, poll: BatteryPoll, now: Long) {
        try {
            app.trips.sample(vin, System.currentTimeMillis(), snapshot.gear, snapshot.speedKph, snapshot.odometerKm,
                battery.value[vin]?.levelPercent)?.let { trip ->
                poll.recheckBy(now)
                note(tripLine(trip))
            }
        } catch (error: Exception) {
            note("주행통계 기록 실패 · ${error.message?.take(160) ?: error.javaClass.simpleName}")
        }
    }

    private fun finishTrip(vin: String) {
        runCatching { app.trips.finish(vin) }.getOrNull()?.let { note(tripLine(it)) }
    }

    private fun tripLine(trip: dev.teslanavi.logbook.DriveTrip) =
        String.format(Locale.KOREA, "주행통계 · 주행 기록 추가 %.1f km · %d분", trip.distanceKm, trip.durationMs / 60_000)

    /** The battery read feeds the logbook too; while a cable is in, read again soon so the unplug odometer is accurate. */
    private fun recordCharge(vin: String, charge: ChargeSnapshot, odometerKm: Double?, poll: BatteryPoll, now: Long) {
        val message = try {
            val detection = app.logbook.observe(vin, charge, odometerKm)
            if (detection == ChargeDetection.PluggedIn) poll.recheckBy(now + BatteryPoll.PLUGGED_RECHECK)
            when (detection) {
                ChargeDetection.PluggedIn -> "충전통계 · 충전 케이블 연결됨 · 분리 후 기록"
                ChargeDetection.MissingEnergy -> "충전통계 · 차량이 충전량을 제공하지 않음"
                ChargeDetection.Baseline -> "충전통계 · 마지막 충전량을 기준으로 저장"
                ChargeDetection.Unchanged -> "충전통계 · 새 충전 없음"
                ChargeDetection.MissingOdometer -> "충전통계 · 주행거리 정보가 없어 기록 대기"
                ChargeDetection.AlreadyLogged -> "충전통계 · 같은 주행거리의 충전 기록이 있어 건너뜀"
                is ChargeDetection.NewCharge -> String.format(Locale.KOREA, "충전통계 · 충전 기록 추가 %.1f kWh · %,.0f km",
                    detection.energyKwh, detection.odometerKm)
            }
        } catch (error: Exception) {
            "충전통계 기록 실패 · ${error.message?.take(160) ?: error.javaClass.simpleName}"
        }
        if (message != lastChargeNote) note(message)
        lastChargeNote = message
    }
}
