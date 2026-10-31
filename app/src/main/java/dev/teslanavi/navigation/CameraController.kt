package dev.teslanavi.navigation

import dev.teslanavi.drive.DrivePhase
import dev.teslanavi.drive.DriveState
import dev.teslanavi.drive.TripMotionGate
import dev.teslanavi.protocol.NavigationDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CameraController(
    private val engine: CameraNavigationEngine,
    private val speaker: CameraSpeaker,
    private val settings: StateFlow<GuidanceOptions> = MutableStateFlow(GuidanceOptions()),
    private val walkingSteps: Flow<Long> = emptyFlow(),
    private val onContinuation: (String) -> Unit = {},
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow(CameraState())
    val state = _state.asStateFlow()
    private val policy = CameraAlertPolicy()
    private val overSpeedPolicy = OverSpeedPolicy()
    private var tripVin: String? = null
    private var tripAnnounced = false
    private var pendingTrip = false
    private var acceptingFrames = false
    private var audioGeneration = 0L
    private var tripSpeech: Any? = null

    fun configured(hasKey: Boolean) {
        _state.value = CameraState(phase = if (hasKey) CameraPhase.IDLE else CameraPhase.KEY_REQUIRED,
            message = if (hasKey) "차량 연결 후 주행을 시작하면 카메라를 안내합니다" else "이 설치 파일에는 카카오 안내 키가 없습니다. 앱 업데이트가 필요합니다.",
            muted = _state.value.muted)
    }

    fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
        if (muted) { audioGeneration++; tripSpeech = null; speaker.setOverSpeedAlert(false); speaker.silence() }
    }

    fun blocked(message: String) {
        invalidate()
        _state.update { it.copy(phase = CameraPhase.ERROR, message = message, error = message) }
    }

    private fun invalidate() {
        audioGeneration++
        tripSpeech = null
        acceptingFrames = false
        policy.clear()
        overSpeedPolicy.clear()
        speaker.setOverSpeedAlert(false)
        speaker.silence()
        _state.update { it.copy(cameras = emptyList(), locationAtMillis = null, currentSpeedKph = null,
            roadClass = RoadClass.UNKNOWN, overSpeed = false, overSpeedCamera = null) }
    }

    suspend fun run(nativeKey: String, drive: StateFlow<DriveState>) {
        if (nativeKey.isBlank()) { configured(false); return }
        try {
            coroutineScope {
                _state.update { it.copy(phase = CameraPhase.WAITING_VEHICLE, message = "차량 연결을 기다리고 있습니다", error = null,
                    phoneFallback = false, continuationExpired = false, vehicleAvailable = false, continuationEndReason = null) }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    settings.collect { speaker.setVolume(it.volumePercent) }
                }
                val movement = TripMotionGate()
                val continuation = GuidanceContinuation()
                val tick = MutableStateFlow(clock())
                launch {
                    var fixVin: String? = null
                    drive.map { Triple(it.vin, it.phase, it.updatedAt to it.snapshot) }.distinctUntilChanged().collect { (vin, phase, data) ->
                        if (fixVin != vin) { engine.updateVehicleFix(null); fixVin = vin }
                        val (updatedAt, snapshot) = data
                        val age = updatedAt?.let { System.currentTimeMillis() - it }
                        engine.updateVehicleFix(if (phase == DrivePhase.CONNECTED && age != null && age in 0..10_000)
                            snapshot?.location?.let { VehicleGuidanceFix(it, snapshot.speedKph, snapshot.gear,
                                clock() - age, updatedAt) } else null)
                    }
                }
                launch {
                    state.map { it.phoneFallback }.distinctUntilChanged().collectLatest { active ->
                        if (active) while (currentCoroutineContext().isActive) {
                            try { walkingSteps.collect { continuation.step(it, clock()) } }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { /* Optional sensor failure must never end driving guidance. */ }
                            delay(10_000) // Permission may be granted while a disconnected trip is running.
                        }
                    }
                }
                var initialized = false
                var voiceInitialized = false
                suspend fun initializeVoice() {
                    _state.update { it.copy(voiceError = "음성 안내를 준비하고 있습니다. 경고음은 계속 제공합니다.") }
                    val error = try { speaker.initialize() }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { "음성 엔진을 시작하지 못했습니다. 자동으로 다시 시도합니다." }
                    _state.update { it.copy(voiceError = error) }
                    voiceInitialized = error == null
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    engine.events.collect { update ->
                        if (!acceptingFrames || update.revision != engine.revision) return@collect
                        val event = update.event
                        when (event) {
                            is NavigationEvent.Mode -> {
                                if (_state.value.mode != event.mode) {
                                    audioGeneration++
                                    tripSpeech = null
                                    policy.clear(); overSpeedPolicy.clear(); speaker.setOverSpeedAlert(false); speaker.silence()
                                    _state.update { it.copy(cameras = emptyList(), locationAtMillis = null,
                                        currentSpeedKph = null, roadClass = RoadClass.UNKNOWN, overSpeed = false, overSpeedCamera = null) }
                                }
                                _state.update { it.copy(mode = event.mode, message = event.message) }
                            }
                            is NavigationEvent.RouteInvalidated -> {
                                audioGeneration++
                                tripSpeech = null
                                policy.clear(); overSpeedPolicy.clear(); speaker.setOverSpeedAlert(false); speaker.silence()
                                _state.update { it.copy(cameras = emptyList(), locationAtMillis = null,
                                    phase = CameraPhase.WAITING_GPS, message = event.message,
                                    currentSpeedKph = null, roadClass = RoadClass.UNKNOWN, overSpeed = false, overSpeedCamera = null) }
                            }
                            is NavigationEvent.Frame -> {
                                continuation.observe(event, clock())
                                if (continuation.expired) {
                                    speaker.setOverSpeedAlert(false); speaker.silence()
                                    tick.value = clock()
                                    return@collect
                                }
                                val fresh = event.locationAtMillis?.let { clock() - it in 0..10000 } == true
                                val cameras = if (fresh) event.cameras.filter { it.distanceMeters in 0..10000 }
                                    .distinctBy { it.id }.sortedBy { it.distanceMeters } else emptyList()
                                val car = drive.value
                                val carSpeed = car.takeIf { it.phase == DrivePhase.CONNECTED &&
                                    it.updatedAt?.let { time -> System.currentTimeMillis() - time in 0..10_000 } == true }
                                    ?.snapshot?.speedKph?.takeIf { it.isFinite() && it >= 0 }
                                val speed = if (fresh) carSpeed ?: event.speedKph?.takeIf { it >= 0 }?.toDouble() else null
                                val road = if (fresh) event.roadClass else RoadClass.UNKNOWN
                                val speedCamera = cameras.firstOrNull { overSpeedPolicy.isOverSpeed(it, speed, road, settings.value) }
                                val overSpeed = speedCamera != null
                                if (fresh && event.source != _state.value.positionSource) onContinuation(
                                    if (event.source == PositionSource.VEHICLE) "휴대폰 GPS 수신 중단 · 최신 차량 위치로 카메라 보조 안내"
                                    else "휴대폰 GPS 수신 복구 · 카카오 위치 안내로 복귀")
                                _state.update { it.copy(phase = if (fresh) CameraPhase.GUIDING else CameraPhase.WAITING_GPS,
                                    cameras = cameras.take(3), locationAtMillis = event.locationAtMillis,
                                    positionSource = if (fresh) event.source else it.positionSource,
                                    currentSpeedKph = speed, roadClass = road, overSpeed = overSpeed, overSpeedCamera = speedCamera) }
                                speaker.setOverSpeedAlert(overSpeed && !_state.value.muted)
                                if (!fresh && tripSpeech == null) speaker.silence()
                                if (fresh && !_state.value.muted && _state.value.voiceError == null) {
                                    val now = clock()
                                    val presence = cameras.firstNotNullOfOrNull { policy.next(it, road, now, settings.value) }
                                    val speedWarning = overSpeedPolicy.next(speedCamera, speed, road, now, settings.value)
                                    val presenceWithWarning = presence?.takeIf {
                                        speedWarning == null || it.cameraId == speedCamera?.id
                                    }
                                    val text = listOfNotNull(presenceWithWarning?.text, speedWarning?.text).joinToString(" ")
                                    val generation = audioGeneration
                                    if (text.isNotBlank()) speaker.speak(text) { completed ->
                                        if (completed && acceptingFrames && generation == audioGeneration) {
                                            presenceWithWarning?.let(policy::commit)
                                            speedWarning?.let(overSpeedPolicy::commit)
                                        }
                                    }
                                    // After speak(), so preparation never queues ahead of a due announcement.
                                    cameras.forEach { camera ->
                                        policy.upcoming(camera, road, settings.value)?.let(speaker::prefetch)
                                        overSpeedPolicy.upcoming(camera, road, settings.value)?.let(speaker::prefetch)
                                    }
                                }
                            }
                        }
                    }
                }
                launch {
                    state.map { when (it.phase) {
                        CameraPhase.IDLE, CameraPhase.WAITING_DRIVE, CameraPhase.WAITING_VEHICLE -> 5_000L
                        else -> 1_000L
                    } }
                        .distinctUntilChanged().collectLatest { interval ->
                            while (isActive) {
                                delay(interval)
                                tick.value = clock()
                                if (pendingTrip && acceptingFrames && !_state.value.muted && _state.value.voiceError == null) {
                                    val token = Any()
                                    tripSpeech = token
                                    val accepted = speaker.speak("주행시작") {
                                        if (tripSpeech === token) tripSpeech = null
                                    }
                                    // The trip event must not loop when playback is interrupted.
                                    if (accepted) {
                                        pendingTrip = false; tripAnnounced = true
                                    } else if (tripSpeech === token) {
                                        tripSpeech = null
                                    }
                                }
                                if (_state.value.locationAtMillis?.let { clock() - it > 10000 } == true) {
                                    speaker.setOverSpeedAlert(false)
                                    if (tripSpeech == null) speaker.silence()
                                    _state.update { it.copy(phase = CameraPhase.WAITING_GPS, cameras = emptyList(),
                                        locationAtMillis = null, currentSpeedKph = null, roadClass = RoadClass.UNKNOWN,
                                        overSpeed = false, overSpeedCamera = null, message = "현재 위치를 다시 확인하고 있습니다") }
                                }
                            }
                        }
                }
                combine(drive, tick) { current, now ->
                    if (tripVin != current.vin) movement.update(null, false, null)
                    if (tripVin != current.vin || current.snapshot?.gear == dev.teslanavi.protocol.Gear.PARK) {
                        tripVin = current.vin; tripAnnounced = false; pendingTrip = false
                    }
                    val available = current.phase == DrivePhase.CONNECTED && current.snapshot != null
                    // Preserve the motion latch through a lost BLE link, including a reconnect at a traffic light.
                    val moving = if (available || current.phase == DrivePhase.IDLE)
                        movement.update(current.vin, available, current.snapshot) else false
                    val fallback = continuation.update(current, moving, now)
                    if (continuation.expired) {
                        movement.update(null, false, null)
                        pendingTrip = false; tripAnnounced = false
                    }
                    val wasFallback = _state.value.phoneFallback
                    val previousEnd = _state.value.continuationEndReason
                    _state.update { it.copy(phoneFallback = continuation.usingPhone, continuationExpired = continuation.expired,
                        continuationEndReason = continuation.endReason,
                        vehicleAvailable = available,
                        phase = if (continuation.expired) CameraPhase.IDLE else it.phase,
                        mode = if (continuation.expired) null else it.mode,
                        message = if (continuation.expired) continuation.endMessage else it.message) }
                    when {
                        continuation.endReason != null && continuation.endReason != previousEnd -> onContinuation(continuation.endMessage)
                        continuation.usingPhone && !wasFallback -> onContinuation("차량 응답 중단 · 마지막 목적지와 휴대폰 GPS로 안내 유지 · 하차 여부 미확정")
                        wasFallback && available -> onContinuation("차량 재연결 확인 · 최신 차량 정보로 복귀")
                    }
                    GuidanceTarget(current.vin, available || fallback != null, moving || fallback != null,
                        if (available) current.snapshot?.destination else fallback)
                }.distinctUntilChanged { old, new -> old.vin == new.vin && old.available == new.available &&
                    old.moving == new.moving && (!new.moving || sameGuidanceDestination(old.destination, new.destination))
                }.collectLatest { (_, available, moving, destination) ->
                    invalidate()
                    engine.pause()
                    if (!available || destination == null) {
                        _state.update { it.copy(phase = if (it.continuationExpired) CameraPhase.IDLE else CameraPhase.WAITING_VEHICLE,
                            mode = null, message = if (it.continuationExpired) continuation.endMessage
                            else "차량 재연결을 기다리고 있습니다") }
                    } else if (!moving) {
                        _state.update { it.copy(phase = CameraPhase.WAITING_DRIVE, mode = null,
                            message = "주행 시작 대기 · 차량 속도 3 km/h 이상에서 안내합니다") }
                    } else {
                        var failures = 0
                        while (currentCoroutineContext().isActive) {
                            try {
                                engine.prepareGuidance()
                                if (!initialized || engine.needsInitialization) {
                                    _state.update { it.copy(phase = CameraPhase.INITIALIZING, message = "카카오내비를 준비하고 있습니다", error = null) }
                                    engine.initialize(nativeKey)
                                    initialized = true
                                }
                                if (!voiceInitialized || speaker.needsInitialization) initializeVoice()
                                pendingTrip = !tripAnnounced
                                acceptingFrames = true
                                _state.update { it.copy(phase = CameraPhase.WAITING_GPS, message = "현재 위치를 확인하고 있습니다", error = null) }
                                engine.guide(destination)
                                break
                            } catch (error: Exception) {
                                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                                currentCoroutineContext().ensureActive()
                                invalidate()
                                engine.pause()
                                val waitSeconds = (5 * ++failures).coerceAtMost(30)
                                _state.update { it.copy(phase = CameraPhase.ERROR,
                                    message = "안내 준비 실패 · ${waitSeconds}초 후 자동 재시도",
                                    error = error.message?.take(240)) }
                                delay(waitSeconds * 1000L)
                            }
                        }
                        // Owned by this guidance target: parking, a different vehicle or fallback expiry cancels it.
                        while (currentCoroutineContext().isActive) {
                            delay(5000)
                            if (!voiceInitialized || speaker.needsInitialization) initializeVoice()
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            _state.update { it.copy(phase = CameraPhase.ERROR, error = "카카오 응답 시간이 초과되었습니다. 네트워크를 확인한 뒤 다시 연결해 주세요.",
                message = "카카오 연결을 확인해 주세요") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(phase = CameraPhase.ERROR, error = error.message?.take(240) ?: "카카오 안내를 시작하지 못했습니다",
                message = "카카오 연결을 확인해 주세요") }
        } catch (_: LinkageError) {
            _state.update { it.copy(phase = CameraPhase.ERROR, error = "이 기기에서 카카오 SDK를 불러오지 못했습니다.", message = "기기 호환성을 확인해 주세요") }
        } finally {
            engine.updateVehicleFix(null)
            invalidate(); engine.pause(); speaker.close()
            _state.update { it.copy(phoneFallback = false, vehicleAvailable = false) }
            if (_state.value.phase != CameraPhase.ERROR && !_state.value.continuationExpired) configured(true)
        }
    }

    private data class GuidanceTarget(val vin: String?, val available: Boolean, val moving: Boolean, val destination: NavigationDestination?)
}
