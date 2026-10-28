package dev.teslanavi.navigation

import dev.teslanavi.drive.DrivePhase
import dev.teslanavi.drive.DriveState
import dev.teslanavi.drive.runWithGuidanceRecovery
import dev.teslanavi.protocol.DriveSnapshot
import dev.teslanavi.protocol.GeoPoint
import dev.teslanavi.protocol.Gear
import dev.teslanavi.protocol.NavigationDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraControllerTest {
    @Test fun freshVehicleFramesWarnAndPhoneRecoveryDoesNotRepeatPresence() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        engine.frame(listOf(camera), 0, speed = 70, source = PositionSource.VEHICLE); runCurrent()
        assertEquals(PositionSource.VEHICLE, controller.state.value.positionSource)
        assertTrue(speaker.alarm)
        val spoken = speaker.spoken.size
        engine.frame(listOf(camera), 0, speed = 70); runCurrent()
        assertEquals(PositionSource.PHONE, controller.state.value.positionSource)
        assertEquals(spoken, speaker.spoken.size)
        engine.frame(listOf(camera), -11_000, speed = 70, source = PositionSource.VEHICLE); runCurrent()
        assertFalse(speaker.alarm)
        assertTrue(controller.state.value.cameras.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun vehicleFixIsForwardedOncePerSnapshotAndClearedOnDisconnection() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val initial = connected()
        val drive = MutableStateFlow(initial.copy(snapshot = initial.snapshot!!.copy(location =
            dev.teslanavi.protocol.VehicleLocation(GeoPoint(37.5, 127.0), 90, System.currentTimeMillis() / 1000)),
            updatedAt = System.currentTimeMillis()))
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        assertNotNull(engine.fixes.last())
        val count = engine.fixes.size
        advanceTimeBy(5001); runCurrent()
        assertEquals(count, engine.fixes.size)
        drive.value = drive.value.copy(phase = DrivePhase.RECONNECTING); runCurrent()
        assertNull(engine.fixes.last())
        job.cancelAndJoin()
    }
    @Test fun staleGpsDoesNotInterruptTripStartOrRepeatItAfterAnInterruptedPlayback() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker().apply { deferCompletion = true }
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        advanceTimeBy(1001); runCurrent()
        assertEquals(1, speaker.tripStarts)
        val silences = speaker.silenceCalls
        repeat(5) {
            engine.frame(emptyList(), testScheduler.currentTime - 11_000); runCurrent()
            advanceTimeBy(1000); runCurrent()
        }
        assertEquals("GPS validity must not stop the departure message", silences, speaker.silenceCalls)
        speaker.finish(false)
        repeat(60) {
            engine.frame(emptyList(), testScheduler.currentTime - 11_000); runCurrent()
            advanceTimeBy(1000); runCurrent()
        }
        assertEquals("interrupted departure is not a recurring warning", 1, speaker.tripStarts)
        job.cancelAndJoin()
    }

    @Test fun rejectedTripStartRetriesButAcceptedTripStartDoesNotRepeatAcrossRouteChange() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker().apply { acceptsAudio = false; deferCompletion = true }
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent(); advanceTimeBy(1001); runCurrent()
        assertEquals(0, speaker.tripStarts)
        speaker.acceptsAudio = true
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, speaker.tripStarts)
        // A route change can cancel playback before onDone arrives.
        drive.value = connected(NavigationDestination.None); runCurrent()
        speaker.finish(false)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, speaker.tripStarts)
        drive.value = connected(speed = 0.0, gear = Gear.PARK); runCurrent()
        drive.value = connected(); runCurrent()
        advanceTimeBy(1001); runCurrent()
        assertEquals(2, speaker.tripStarts)
        job.cancelAndJoin()
    }
    @Test fun failedVoiceRecoversDuringSameDriveWithoutRestartingRouteOrChime() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker().apply { initializationError = "temporary TTS failure" }
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera), 0, speed = 70); runCurrent()
        assertTrue(speaker.alarm)
        assertTrue(speaker.spoken.isEmpty())
        speaker.initializationError = null
        advanceTimeBy(5001); runCurrent()
        assertNull(controller.state.value.voiceError)
        assertTrue(speaker.alarm)
        engine.frame(listOf(camera), testScheduler.currentTime, speed = 70); runCurrent()
        assertEquals(1, speaker.spoken.size)
        assertEquals(2, speaker.initializations)
        assertEquals(1, engine.destinations.size)
        // A later TTS service failure also recovers without a destination change.
        speaker.needsInitialization = true
        advanceTimeBy(5000); runCurrent()
        assertEquals(3, speaker.initializations)
        assertEquals(1, engine.destinations.size)
        job.cancelAndJoin()
    }

    @Test fun parkingCancelsVoiceRecoveryInProgress() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker().apply { initializationError = "temporary TTS failure" }
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        speaker.initializationGate = gate
        speaker.initializationError = null
        advanceTimeBy(5001); runCurrent()
        assertEquals(2, speaker.initializations)
        drive.value = connected(speed = 0.0, gear = Gear.PARK); runCurrent()
        gate.complete(Unit)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(CameraPhase.WAITING_DRIVE, controller.state.value.phase)
        assertEquals(2, speaker.initializations)
        assertTrue(speaker.spoken.isEmpty())
        assertEquals(0, speaker.tripStarts)
        job.cancelAndJoin()
    }

    @Test fun asynchronousSpeechFailureDoesNotConsumeCameraOrOverspeedAnnouncement() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker().apply { deferCompletion = true }
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera), 0, speed = 70); runCurrent()
        engine.frame(listOf(camera), 0, speed = 70); runCurrent()
        assertEquals(1, speaker.spoken.size)
        speaker.finish(false)
        engine.frame(listOf(camera.copy(distanceMeters = 100)), 0, speed = 70); runCurrent()
        assertEquals(2, speaker.spoken.size)
        assertTrue(speaker.spoken.last().contains("100미터"))
        assertTrue(speaker.spoken.last().contains("초과입니다"))
        speaker.finish(true)
        engine.frame(listOf(camera), 0, speed = 70); runCurrent()
        assertEquals(2, speaker.spoken.size)
        job.cancelAndJoin()
    }

    @Test fun completionFromPreviousRouteCannotConsumeNewRouteAnnouncement() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker().apply { deferCompletion = true }
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        engine.frame(listOf(camera), 0); runCurrent()
        drive.value = connected(NavigationDestination.None); runCurrent()
        speaker.finish(true)
        engine.frame(listOf(camera), 0); runCurrent()
        assertEquals(2, speaker.spoken.size)
        job.cancelAndJoin()
    }

    @Test fun unknownNearbyCameraDoesNotMaskOverspeedCameraEvenBeyondDisplayLimit() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        val unknown = (1..3).map { camera.copy(id = "unknown-$it", distanceMeters = it * 30, speedLimitKph = null) }
        engine.frame(unknown + camera.copy(distanceMeters = 200), 0, speed = 70); runCurrent()
        assertTrue(speaker.alarm)
        assertTrue(speaker.spoken.single().contains("제한속도 60킬로미터 초과입니다"))
        assertEquals(3, controller.state.value.cameras.size)
        job.cancelAndJoin()
    }

    @Test fun initializationRetriesAndRecoversWithoutVehicleReconnect() = runTest {
        val engine = FakeNavigationEngine().apply { initializationError = IllegalStateException("temporary failure") }
        val controller = CameraController(engine, FakeSpeaker()) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        assertEquals(CameraPhase.ERROR, controller.state.value.phase)
        engine.initializationError = null
        advanceTimeBy(5001); runCurrent()
        assertEquals(listOf(destination), engine.destinations)
        assertNull(controller.state.value.error)
        job.cancelAndJoin()
    }

    @Test fun parkingCancelsPendingRetryAndLongStopCanResumeGuidance() = runTest {
        val engine = FakeNavigationEngine().apply { initializationError = IllegalStateException("temporary failure") }
        val controller = CameraController(engine, FakeSpeaker()) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        drive.value = connected(speed = 0.0, gear = Gear.PARK); runCurrent()
        engine.initializationError = null
        advanceTimeBy(600_000); runCurrent()
        assertTrue(engine.destinations.isEmpty())
        drive.value = connected(); runCurrent()
        assertEquals(listOf(destination), engine.destinations)
        job.cancelAndJoin()
    }
    private val destination = NavigationDestination.Active("서울역", GeoPoint(37.555, 126.971))
    private val camera = CameraReading("camera-a", CameraKind.SPEED, 60, 400)
    private fun connected(goal: NavigationDestination = destination, vin: String = "vehicle-a", speed: Double? = 10.0, gear: Gear = Gear.DRIVE) = DriveState(
        vin = vin, phase = DrivePhase.CONNECTED, snapshot = DriveSnapshot(goal, speed, null, null, gear))

    @Test fun approachingCameraIsPreparedWithTheExactSentenceLaterAnnounced() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera.copy(distanceMeters = 1250), camera.copy(id = "far", distanceMeters = 1400)), 0, road = RoadClass.GENERAL)
        runCurrent()
        assertTrue(speaker.spoken.isEmpty())
        assertEquals(listOf("300미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 60킬로미터입니다.", "제한속도 60킬로미터 초과입니다."),
            speaker.prefetched)
        speaker.prefetched.clear()
        engine.frame(listOf(camera.copy(distanceMeters = 281)), 0, road = RoadClass.GENERAL)
        runCurrent()
        assertEquals(listOf("300미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 60킬로미터입니다."), speaker.spoken)
        assertEquals("announced camera is not prepared again", listOf("제한속도 60킬로미터 초과입니다."), speaker.prefetched)
        job.cancelAndJoin()
    }

    @Test fun changingDistanceAppliesToNextFrameWithoutRestartingRoute() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val options = MutableStateFlow(GuidanceOptions())
        val controller = CameraController(engine, speaker, options) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera.copy(distanceMeters = 700)), 0, road = RoadClass.HIGHWAY, speed = 65)
        runCurrent()
        assertFalse(speaker.alarm)
        assertTrue(speaker.spoken.isEmpty())
        options.value = GuidanceOptions(highwayMeters = 800)
        runCurrent()
        engine.frame(listOf(camera.copy(distanceMeters = 700)), 0, road = RoadClass.HIGHWAY, speed = 65)
        runCurrent()
        assertTrue(speaker.alarm)
        assertTrue(speaker.spoken.single().contains("700미터"))
        assertEquals(1, engine.destinations.size)
        options.value = GuidanceOptions(highwayMeters = 400)
        runCurrent()
        engine.frame(listOf(camera.copy(distanceMeters = 700)), 0, road = RoadClass.HIGHWAY, speed = 65)
        runCurrent()
        assertFalse(speaker.alarm)
        job.cancelAndJoin()
    }

    @Test fun tripStartIsOnceAcrossDestinationAndReconnectAndRearmsInPark() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent(); advanceTimeBy(1001); runCurrent()
        assertEquals(1, speaker.tripStarts)
        drive.value = connected(NavigationDestination.None); runCurrent()
        advanceTimeBy(1001); runCurrent()
        drive.value = drive.value.copy(phase = DrivePhase.RECONNECTING, snapshot = null); runCurrent()
        drive.value = connected(); runCurrent(); advanceTimeBy(1001); runCurrent()
        assertEquals(1, speaker.tripStarts)
        drive.value = connected(speed = 0.0, gear = Gear.PARK); runCurrent()
        drive.value = connected(); runCurrent(); advanceTimeBy(1001); runCurrent()
        assertEquals(2, speaker.tripStarts)
        job.cancelAndJoin()
    }

    @Test fun chimeStopsForSlowingMuteAndStaleGps() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera), 0, speed = 65); runCurrent()
        assertTrue(speaker.alarm)
        engine.frame(listOf(camera), 0, speed = 64); runCurrent()
        assertFalse(speaker.alarm)
        engine.frame(listOf(camera), 0, speed = 65); runCurrent()
        assertTrue(speaker.alarm)
        controller.setMuted(true)
        assertFalse(speaker.alarm)
        controller.setMuted(false)
        engine.frame(listOf(camera), 0, speed = 65); runCurrent()
        assertTrue(speaker.alarm)
        advanceTimeBy(11001); runCurrent()
        assertFalse(speaker.alarm)
        job.cancelAndJoin()
    }

    @Test fun generalRoadWaitsUntilThreeHundredMetersAndCombinesPresenceWithSpeedWarning() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera.copy(distanceMeters = 301)), 0, road = RoadClass.GENERAL, speed = 65); runCurrent()
        assertTrue(speaker.spoken.isEmpty())
        engine.frame(listOf(camera.copy(distanceMeters = 300)), 0, road = RoadClass.GENERAL, speed = 65); runCurrent()
        assertTrue(controller.state.value.overSpeed)
        assertEquals(1, speaker.spoken.size)
        assertTrue(speaker.spoken.single().contains("카메라가 있습니다"))
        assertTrue(speaker.spoken.single().contains("제한속도 60킬로미터 초과입니다"))
        engine.frame(listOf(camera.copy(distanceMeters = 200)), 0, road = RoadClass.GENERAL, speed = 65); runCurrent()
        assertEquals(1, speaker.spoken.size)
        job.cancelAndJoin()
    }

    @Test fun slowHighwayTrafficStillGetsFiveHundredMeterPresenceWarning() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera.copy(distanceMeters = 500)), 0, road = RoadClass.HIGHWAY, speed = 30); runCurrent()
        assertEquals(1, speaker.spoken.size)
        assertFalse(controller.state.value.overSpeed)
        job.cancelAndJoin()
    }

    @Test fun freshTeslaSpeedTakesPriorityButStaleVehicleSpeedFallsBackToTrustedGps() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected(speed = 60.0).copy(updatedAt = System.currentTimeMillis()))
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        engine.frame(listOf(camera), 0, speed = 65); runCurrent()
        assertFalse(controller.state.value.overSpeed)
        assertEquals(60.0, controller.state.value.currentSpeedKph!!, 0.001)
        drive.value = drive.value.copy(updatedAt = System.currentTimeMillis() - 11_000); runCurrent()
        engine.frame(listOf(camera), 0, speed = 65); runCurrent()
        assertTrue(controller.state.value.overSpeed)
        assertEquals(2, speaker.spoken.size)
        engine.frame(listOf(camera), -10_001, speed = 65); runCurrent()
        assertFalse(controller.state.value.overSpeed)
        assertNull(controller.state.value.currentSpeedKph)
        job.cancelAndJoin()
    }

    @Test fun rejectedCombinedAlertRetriesAndMuteKeepsSpeedWarningVisible() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker().apply { acceptsAudio = false }
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera), 0, speed = 65); runCurrent()
        assertTrue(speaker.spoken.isEmpty())
        assertTrue(controller.state.value.overSpeed)
        speaker.acceptsAudio = true
        engine.frame(listOf(camera), 0, speed = 65); runCurrent()
        assertEquals(1, speaker.spoken.size)
        controller.setMuted(true)
        advanceTimeBy(15_000); runCurrent()
        engine.frame(listOf(camera), testScheduler.currentTime, speed = 65); runCurrent()
        assertTrue(controller.state.value.overSpeed)
        assertEquals(1, speaker.spoken.size)
        controller.setMuted(false)
        engine.frame(listOf(camera), testScheduler.currentTime, speed = 65); runCurrent()
        assertEquals(1, speaker.spoken.size)
        assertTrue(speaker.alarm)
        job.cancelAndJoin()
    }

    @Test fun approachAndDestinationDoNotInitializeSdkUntilVehicleMoves() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected(speed = 0.0, gear = Gear.PARK))
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        assertEquals(CameraPhase.WAITING_DRIVE, controller.state.value.phase)
        assertTrue(engine.keys.isEmpty())
        drive.value = connected(speed = 2.9); runCurrent()
        assertTrue(engine.keys.isEmpty())
        drive.value = connected(speed = 3.0); runCurrent()
        assertEquals(listOf(destination), engine.destinations)
        drive.value = connected(speed = 0.0); runCurrent()
        assertEquals(1, engine.destinations.size)
        engine.frame(listOf(camera), 0); runCurrent()
        assertEquals(1, speaker.spoken.size)
        job.cancelAndJoin()
    }

    @Test fun parkClearsAudioAndRejectsOldFramesThenNewTripStarts() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        engine.frame(listOf(camera), 0); runCurrent()
        val oldRevision = engine.revision
        drive.value = connected(speed = 0.0, gear = Gear.PARK); runCurrent()
        assertEquals(CameraPhase.WAITING_DRIVE, controller.state.value.phase)
        engine.frame(listOf(camera), 0, oldRevision); runCurrent()
        assertTrue(controller.state.value.cameras.isEmpty())
        assertEquals(1, speaker.spoken.size)
        drive.value = connected(speed = null); runCurrent()
        assertEquals(1, engine.destinations.size)
        drive.value = connected(); runCurrent()
        engine.frame(listOf(camera), 0); runCurrent()
        assertEquals(2, speaker.spoken.size)
        assertEquals(1, engine.keys.size)
        job.cancelAndJoin()
    }

    @Test fun parkingDuringSdkInitializationCancelsRouteStart() = runTest {
        val ready = CompletableDeferred<Unit>()
        val engine = FakeNavigationEngine().apply { initializationGate = ready }
        val controller = CameraController(engine, FakeSpeaker()) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        assertEquals(CameraPhase.INITIALIZING, controller.state.value.phase)
        drive.value = connected(speed = 0.0, gear = Gear.PARK); runCurrent()
        ready.complete(Unit); runCurrent()
        assertEquals(CameraPhase.WAITING_DRIVE, controller.state.value.phase)
        assertTrue(engine.destinations.isEmpty())
        drive.value = connected(); runCurrent()
        assertEquals(listOf(destination), engine.destinations)
        job.cancelAndJoin()
    }

    @Test fun missingKeyNeverInitializesSdk() = runTest {
        val engine = FakeNavigationEngine()
        val controller = CameraController(engine, FakeSpeaker()) { testScheduler.currentTime }
        controller.run("", MutableStateFlow(connected()))
        assertEquals(CameraPhase.KEY_REQUIRED, controller.state.value.phase)
        assertTrue(engine.keys.isEmpty())
    }

    @Test fun waitsForVehicleAndSwitchesDestinationAndSafetyMode() = runTest {
        val engine = FakeNavigationEngine()
        val controller = CameraController(engine, FakeSpeaker()) { testScheduler.currentTime }
        val drive = MutableStateFlow(DriveState())
        val job = backgroundScope.launch { controller.run("configured-key", drive) }
        runCurrent()
        assertTrue(engine.keys.isEmpty())
        drive.value = connected()
        runCurrent()
        assertEquals(listOf(destination), engine.destinations)
        drive.value = connected(NavigationDestination.None)
        runCurrent()
        assertEquals(NavigationDestination.None, engine.destinations.last())
        assertEquals(NavigationMode.SAFETY, controller.state.value.mode)
        job.cancelAndJoin()
    }

    @Test fun destinationChangeDropsAlreadyQueuedEventsFromPreviousRoute() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        engine.frame(listOf(camera), testScheduler.currentTime); runCurrent()
        assertEquals(1, controller.state.value.cameras.size)
        val oldRevision = engine.revision
        drive.value = connected(NavigationDestination.Active("부산역", GeoPoint(35.115, 129.042)))
        runCurrent()
        assertTrue(controller.state.value.cameras.isEmpty())
        val count = speaker.spoken.size
        engine.frame(listOf(camera.copy(id = "old-route")), testScheduler.currentTime, oldRevision)
        runCurrent()
        assertTrue(controller.state.value.cameras.isEmpty())
        assertEquals(count, speaker.spoken.size)
        job.cancelAndJoin()
    }

    @Test fun staleGpsCannotShowOrSpeakAndWatchdogClearsLastFrame() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera), -10001); runCurrent()
        assertTrue(controller.state.value.cameras.isEmpty())
        assertTrue(speaker.spoken.isEmpty())
        engine.frame(listOf(camera), testScheduler.currentTime); runCurrent()
        assertEquals(CameraPhase.GUIDING, controller.state.value.phase)
        advanceTimeBy(11001); runCurrent()
        assertTrue(controller.state.value.cameras.isEmpty())
        assertEquals(CameraPhase.WAITING_GPS, controller.state.value.phase)
        job.cancelAndJoin()
    }

    @Test fun disconnectKeepsTheRouteButVehicleChangeClearsCameraAndAudioState() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        engine.frame(listOf(camera), 0); runCurrent()
        drive.value = DriveState(vin = "vehicle-a", phase = DrivePhase.RECONNECTING)
        runCurrent()
        engine.frame(listOf(camera), 0); runCurrent()
        assertTrue(controller.state.value.cameras.isNotEmpty())
        assertEquals(CameraPhase.GUIDING, controller.state.value.phase)
        assertTrue(controller.state.value.phoneFallback)
        assertEquals(1, engine.destinations.size)
        assertEquals(1, speaker.spoken.size)
        drive.value = connected(vin = "vehicle-b"); runCurrent()
        assertFalse(controller.state.value.phoneFallback)
        assertTrue(controller.state.value.cameras.isEmpty())
        engine.frame(listOf(camera), 0); runCurrent()
        assertEquals(2, speaker.spoken.size)
        job.cancelAndJoin()
        assertTrue(speaker.closed)
        assertTrue(controller.state.value.cameras.isEmpty())
    }

    @Test fun disconnectedGuidanceUsesPhoneSpeedAndReconnectAtTrafficLightKeepsSameRoute() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected(speed = 10.0).copy(updatedAt = System.currentTimeMillis()))
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        val revision = engine.revision
        drive.value = DriveState(vin = "vehicle-a", phase = DrivePhase.RECONNECTING)
        runCurrent()
        engine.frame(listOf(camera), 0, speed = 70); runCurrent()
        assertTrue(controller.state.value.phoneFallback)
        assertEquals(70.0, controller.state.value.currentSpeedKph!!, 0.01)
        assertTrue(speaker.alarm)
        assertEquals(revision, engine.revision)
        assertEquals(1, engine.destinations.size)
        drive.value = connected(speed = 0.0).copy(updatedAt = System.currentTimeMillis()); runCurrent()
        assertFalse(controller.state.value.phoneFallback)
        assertEquals(revision, engine.revision)
        engine.frame(emptyList(), 0, speed = 50); runCurrent()
        assertEquals(0.0, controller.state.value.currentSpeedKph!!, 0.01)
        drive.value = connected(speed = 0.0, gear = Gear.PARK); runCurrent()
        assertEquals(CameraPhase.WAITING_DRIVE, controller.state.value.phase)
        job.cancelAndJoin()
    }

    @Test fun tenMinutesWithoutMotionStopsDisconnectedGuidanceAndDropsNewFrames() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        drive.value = DriveState(vin = "vehicle-a", phase = DrivePhase.ERROR); runCurrent()
        engine.frame(listOf(camera), 0, speed = 0); runCurrent()
        advanceTimeBy(599_999); runCurrent()
        assertTrue(controller.state.value.phoneFallback)
        advanceTimeBy(1); runCurrent()
        assertTrue(controller.state.value.continuationExpired)
        assertFalse(controller.state.value.phoneFallback)
        assertEquals(CameraPhase.IDLE, controller.state.value.phase)
        assertTrue(controller.state.value.cameras.isEmpty())
        assertFalse(speaker.alarm)
        val speeches = speaker.spoken.size
        engine.frame(listOf(camera.copy(id = "after-stop")), testScheduler.currentTime, speed = 70); runCurrent()
        assertTrue(controller.state.value.cameras.isEmpty())
        assertEquals(speeches, speaker.spoken.size)
        job.cancelAndJoin()
        assertTrue(controller.state.value.continuationExpired)
        assertTrue(speaker.closed)
    }

    @Test fun continuingPhoneMotionAllowsMoreThanTenMinutesAndNewDestinationTakesOverOnReconnect() = runTest {
        val engine = FakeNavigationEngine()
        val controller = CameraController(engine, FakeSpeaker()) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        drive.value = DriveState(vin = "vehicle-a", phase = DrivePhase.RECONNECTING); runCurrent()
        repeat(20) {
            advanceTimeBy(60_000); runCurrent()
            engine.frame(emptyList(), testScheduler.currentTime, speed = 50); runCurrent()
        }
        assertTrue(controller.state.value.phoneFallback)
        assertFalse(controller.state.value.continuationExpired)
        assertEquals(listOf(destination), engine.destinations)
        val newGoal = NavigationDestination.Active("부산역", GeoPoint(35.115, 129.042))
        drive.value = connected(newGoal); runCurrent()
        assertFalse(controller.state.value.phoneFallback)
        assertEquals(listOf(destination, newGoal), engine.destinations)
        job.cancelAndJoin()
    }

    @Test fun explicitStopParkingAndMissingDestinationNeverKeepDisconnectedGuidanceAlive() = runTest {
        for (initial in listOf(connected(NavigationDestination.None), connected(speed = 0.0, gear = Gear.PARK))) {
            val controller = CameraController(FakeNavigationEngine(), FakeSpeaker()) { testScheduler.currentTime }
            val drive = MutableStateFlow(initial)
            val job = backgroundScope.launch { controller.run("key", drive) }
            runCurrent()
            drive.value = DriveState(vin = "vehicle-a", phase = DrivePhase.RECONNECTING); runCurrent()
            assertFalse(controller.state.value.phoneFallback)
            assertEquals(CameraPhase.WAITING_VEHICLE, controller.state.value.phase)
            job.cancelAndJoin()
        }
        val controller = CameraController(FakeNavigationEngine(), FakeSpeaker()) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        drive.value = DriveState(); runCurrent()
        assertFalse(controller.state.value.phoneFallback)
        assertEquals(CameraPhase.WAITING_VEHICLE, controller.state.value.phase)
        job.cancelAndJoin()
    }

    @Test fun serviceRecoveryAndCameraControllerTogetherKeepGuidanceUntilTheGpsIdleDeadline() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val cameraJob = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        val dropout = CompletableDeferred<Unit>()
        var attempts = 0
        var closed = false
        val service = launch {
            try {
                runWithGuidanceRecovery(controller.state, runVehicle = {
                    if (++attempts == 1) dropout.await()
                    else try { awaitCancellation() } finally { closed = true }
                }, onFailure = { drive.value = DriveState(vin = "vehicle-a", phase = DrivePhase.ERROR) },
                    onRetry = { drive.value = DriveState(vin = "vehicle-a", phase = DrivePhase.CONNECTING) })
            } finally { cameraJob.cancelAndJoin() }
        }
        runCurrent()
        dropout.completeExceptionally(java.io.IOException("BLE exhausted")); runCurrent()
        assertTrue(controller.state.value.phoneFallback)
        engine.frame(listOf(camera), 0, speed = 0); runCurrent()
        assertTrue(controller.state.value.cameras.isNotEmpty())
        advanceTimeBy(599_999); runCurrent()
        assertTrue(service.isActive)
        assertFalse(speaker.closed)
        advanceTimeBy(1); runCurrent()
        assertTrue(service.isCompleted)
        assertTrue(closed)
        assertTrue(speaker.closed)
        assertTrue(controller.state.value.continuationExpired)
        assertEquals(CameraPhase.IDLE, controller.state.value.phase)
        assertTrue(controller.state.value.cameras.isEmpty())
    }

    @Test fun sustainedWalkingAfterLinkLossEndsGuidanceAndReleasesTheStepObserver() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val steps = MutableSharedFlow<Long>(extraBufferCapacity = 8)
        val notices = mutableListOf<String>()
        val controller = CameraController(engine, speaker, walkingSteps = steps, onContinuation = { notices += it }) {
            testScheduler.currentTime
        }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        assertEquals(0, steps.subscriptionCount.value)
        drive.value = DriveState(vin = "vehicle-a", phase = DrivePhase.ERROR); runCurrent()
        assertEquals(1, steps.subscriptionCount.value)
        engine.frame(emptyList(), 0, speed = 4, position = NavigationPosition(0.0, 0.0)); runCurrent()
        repeat(120) { index ->
            advanceTimeBy(1000); runCurrent()
            steps.tryEmit(testScheduler.currentTime)
            engine.frame(emptyList(), testScheduler.currentTime, speed = 4, position = NavigationPosition(index + 1.0, 0.0))
            runCurrent()
        }
        advanceTimeBy(1000); runCurrent()
        assertTrue(controller.state.value.continuationExpired)
        assertEquals(ContinuationEndReason.WALKING, controller.state.value.continuationEndReason)
        assertEquals(CameraPhase.IDLE, controller.state.value.phase)
        assertEquals(0, steps.subscriptionCount.value)
        assertTrue(notices.any { it.contains("하차로 추정") })
        assertFalse(speaker.alarm)
        job.cancelAndJoin()
        assertEquals(ContinuationEndReason.WALKING, controller.state.value.continuationEndReason)
    }

    @Test fun mutingStopsCurrentAudioAndSuppressesNewAnnouncements() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        controller.setMuted(true)
        engine.frame(listOf(camera), 0); runCurrent()
        assertTrue(speaker.spoken.isEmpty())
        assertTrue(controller.state.value.cameras.isNotEmpty())
        controller.setMuted(false)
        engine.frame(listOf(camera), 0); runCurrent()
        engine.frame(listOf(camera), 0); runCurrent()
        assertEquals(1, speaker.spoken.size)
        job.cancelAndJoin()
    }

    @Test fun unavailableSpeechKeepsVisualCameraGuidanceWorking() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker().apply { initializationError = "한국어 음성 데이터 없음" }
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera), 0, speed = 70); runCurrent()
        assertTrue(speaker.alarm)
        assertEquals("한국어 음성 데이터 없음", controller.state.value.voiceError)
        assertEquals(1, controller.state.value.cameras.size)
        assertTrue(speaker.spoken.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun rejectedAudioFocusDoesNotConsumeTheAnnouncement() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker().apply { acceptsAudio = false }
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera), 0); runCurrent()
        assertTrue(speaker.spoken.isEmpty())
        speaker.acceptsAudio = true
        engine.frame(listOf(camera), 0); runCurrent()
        engine.frame(listOf(camera), 0); runCurrent()
        assertEquals(1, speaker.spoken.size)
        job.cancelAndJoin()
    }

    @Test fun sdkFailureIsVisibleAndDoesNotChangeVehicleConnection() = runTest {
        val engine = FakeNavigationEngine().apply { initializationError = IllegalStateException("인증 오류") }
        val controller = CameraController(engine, FakeSpeaker()) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        assertEquals(CameraPhase.ERROR, controller.state.value.phase)
        assertEquals("인증 오류", controller.state.value.error)
        assertEquals(DrivePhase.CONNECTED, drive.value.phase)
        job.cancelAndJoin()
    }

    @Test fun repeatedVehiclePollingDoesNotRequestTheSameRouteAgain() = runTest {
        val engine = FakeNavigationEngine()
        val controller = CameraController(engine, FakeSpeaker()) { testScheduler.currentTime }
        val drive = MutableStateFlow(connected())
        val job = backgroundScope.launch { controller.run("key", drive) }
        runCurrent()
        repeat(4) { index ->
            drive.value = connected().copy(snapshot = DriveSnapshot(destination, index.toDouble(), 30.0 - index, 10.0 - index), updatedAt = index.toLong())
            runCurrent()
        }
        assertEquals(1, engine.destinations.size)
        job.cancelAndJoin()
    }

    @Test fun nearestValidCameraIsSelectedWithoutAnnouncingPassedPoints() = runTest {
        val engine = FakeNavigationEngine()
        val speaker = FakeSpeaker()
        val controller = CameraController(engine, speaker) { testScheduler.currentTime }
        val job = backgroundScope.launch { controller.run("key", MutableStateFlow(connected())) }
        runCurrent()
        engine.frame(listOf(camera.copy(id = "passed", distanceMeters = -10),
            camera.copy(id = "far", distanceMeters = 2000), camera,
            camera.copy(id = "near", distanceMeters = 100)), 0)
        runCurrent()
        assertEquals("near", controller.state.value.cameras.first().id)
        assertTrue(speaker.spoken.single().contains("100미터"))
        job.cancelAndJoin()
    }
}

private class FakeNavigationEngine : CameraNavigationEngine {
    val fixes = mutableListOf<VehicleGuidanceFix?>()
    override fun updateVehicleFix(fix: VehicleGuidanceFix?) { fixes += fix }
    override val events = MutableSharedFlow<NavigationUpdate>(extraBufferCapacity = 32)
    override var revision = 0L
    val keys = mutableListOf<String>()
    val destinations = mutableListOf<NavigationDestination>()
    var initializationError: Exception? = null
    var initializationGate: Deferred<Unit>? = null
    override suspend fun initialize(nativeKey: String) { initializationError?.let { throw it }; initializationGate?.await(); keys += nativeKey }
    override suspend fun guide(destination: NavigationDestination) {
        revision++
        destinations += destination
        val mode = if (destination is NavigationDestination.Active) NavigationMode.ROUTE else NavigationMode.SAFETY
        events.emit(NavigationUpdate(revision, NavigationEvent.Mode(mode, "test mode")))
    }
    override fun pause() { revision++ }
    fun frame(cameras: List<CameraReading>, locationAt: Long, atRevision: Long = revision,
        road: RoadClass = RoadClass.UNKNOWN, speed: Int? = 50, position: NavigationPosition? = null, source: PositionSource = PositionSource.PHONE) {
        events.tryEmit(NavigationUpdate(atRevision, NavigationEvent.Frame(cameras, speed, locationAt, road, position, source)))
    }
}

private class FakeSpeaker : CameraSpeaker {
    var silenceCalls = 0
    override var needsInitialization = false
    var initializations = 0
    var initializationGate: Deferred<Unit>? = null
    var deferCompletion = false
    private var completion: ((Boolean) -> Unit)? = null
    fun finish(success: Boolean) { val callback = completion; completion = null; callback?.invoke(success) }
    override fun speak(text: String, onComplete: (Boolean) -> Unit): Boolean {
        if (completion != null || !speak(text)) return false
        if (deferCompletion) completion = onComplete else onComplete(true)
        return true
    }
    val spoken = mutableListOf<String>()
    val prefetched = mutableListOf<String>()
    override fun prefetch(text: String) { prefetched += text }
    var tripStarts = 0
    var alarm = false
    override fun setOverSpeedAlert(active: Boolean) { alarm = active }
    var acceptsAudio = true
    var closed = false
    var initializationError: String? = null
    override suspend fun initialize(): String? {
        initializations++
        initializationGate?.await()
        needsInitialization = initializationError != null
        return initializationError
    }
    override fun speak(text: String): Boolean { if (acceptsAudio) { if (text == "주행시작") tripStarts++ else spoken += text }; return acceptsAudio }
    override fun silence() { silenceCalls++ }
    override fun close() { closed = true }
}
