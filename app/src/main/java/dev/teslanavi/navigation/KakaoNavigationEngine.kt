@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
package dev.teslanavi.navigation

import android.app.Application
import android.os.SystemClock
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNRoadType
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.gps.KNGPSData
import com.kakaomobility.knsdk.common.gps.KNGPSReceiver
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.guidance.knguidance.*
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafetyCode
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.trip.kntrip.KNTrip
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import dev.teslanavi.BuildConfig
import dev.teslanavi.protocol.NavigationDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Public, typed SDK calls only. The SDK owns GPS acquisition, road matching and rerouting. */
class KakaoNavigationEngine(private val app: Application, private val settings: KakaoSettings) : CameraNavigationEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updates = MutableSharedFlow<NavigationUpdate>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = updates.asSharedFlow()
    override var revision = 0L
        private set
    private var operation = 0L
    private var installed = false
    private var initializedKey: String? = null
    private var initialization: Deferred<Unit>? = null
    private val initializationMutex = Mutex()
    private var guidance: KNDriveGuidance? = null
    private var location: KNGuide_Location? = null
    private var safeties: List<KNSafety> = emptyList()
    private var lastGpsTimestamp: Long? = null
    private var locationAtMillis: Long? = null
    private var mode = NavigationMode.SAFETY
    private var routeInvalid = false
    private var expectedRoute: KNRoute? = null

    override suspend fun initialize(nativeKey: String) = initializationMutex.withLock {
        // An SDK initializer is process-wide. Caller cancellation does not start a competing initializer.
        initialization?.let { previous ->
            try { previous.await() }
            catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
            catch (_: Exception) { }
        }
        if (initializedKey == nativeKey) return@withLock
        val task = scope.async {
            if (!installed) {
                KNSDK.install(app, app.noBackupFilesDir.resolve("kakao-navigation").absolutePath)
                installed = true
            }
            withTimeout(90000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    KNSDK.initializeWithAppKey(aAppKey = nativeKey, aClientVersion = BuildConfig.VERSION_NAME,
                        aCsId = null, aAppUserId = settings.userId, aLangType = KNLanguageType.KNLanguageType_KOREAN) { error ->
                        if (continuation.isActive) {
                            if (error == null) continuation.resume(Unit) else continuation.resumeWithException(sdkError(error))
                        }
                    }
                }
            }
            initializedKey = nativeKey
        }
        initialization = task
        task.await()
    }

    override suspend fun guide(destination: NavigationDestination) {
        pause()
        val token = operation
        val active = KNSDK.sharedGuidance() ?: error("카카오 안내 엔진을 준비하지 못했습니다.")
        guidance = active
        attach(active, token)
        mode = NavigationMode.SAFETY
        routeInvalid = false
        active.useBackgroundUpdate = true
        active.useAutoReroute = true
        active.useDirSound = false
        active.startWithoutTrip()
        publish(NavigationEvent.Mode(NavigationMode.SAFETY, "현재 도로의 단속카메라를 안내합니다"))
        val point = (destination as? NavigationDestination.Active)?.point ?: return
        publish(NavigationEvent.Mode(NavigationMode.SAFETY, "차량 목적지까지 카카오 경로를 탐색합니다"))
        try {
            val gps = awaitGps(token)
            val goalPos = KNSDK.convertWGS84ToKATEC(point.longitude, point.latitude)
            val start = KNPOI("현재 위치", gps.pos.x.roundToInt(), gps.pos.y.roundToInt())
            val goal = KNPOI(destination.name ?: "차량 목적지", goalPos.x.roundToInt(), goalPos.y.roundToInt())
            val trip = withTimeout(30000) {
                suspendCancellableCoroutine<KNTrip> { continuation ->
                    KNSDK.makeTripWithStart(start, goal, null) { error, result ->
                        if (continuation.isActive) {
                            if (error != null) continuation.resumeWithException(sdkError(error))
                            else if (result == null) continuation.resumeWithException(IllegalStateException("카카오 경로를 생성하지 못했습니다."))
                            else continuation.resume(result)
                        }
                    }
                }
            }
            withTimeout(30000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    trip.routeWithPriority(KNRoutePriority.KNRoutePriority_Recommand, 0) { error, routes ->
                        if (continuation.isActive) {
                            if (error != null) continuation.resumeWithException(sdkError(error))
                            else if (routes.isNullOrEmpty()) continuation.resumeWithException(IllegalStateException("카카오 경로 탐색 결과가 없습니다."))
                            else continuation.resume(Unit)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (token != operation) return
            detach(active)
            active.stop()
            clearRouteData()
            mode = NavigationMode.ROUTE
            routeInvalid = false
            attach(active, token)
            active.startWithTrip(trip, KNRoutePriority.KNRoutePriority_Recommand, 0)
            expectedRoute = active.routesOnGuide?.firstOrNull()
            publish(NavigationEvent.Mode(NavigationMode.ROUTE, "카카오 경로의 단속카메라를 안내합니다"))
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            if (token == operation) publish(NavigationEvent.Mode(NavigationMode.SAFETY, "경로 응답 지연 · 현재 도로 기준으로 안내합니다"))
        } catch (error: Exception) {
            if (token == operation) publish(NavigationEvent.Mode(NavigationMode.SAFETY,
                "경로 탐색 실패 · 현재 도로 기준으로 안내합니다${(error as? KakaoSdkException)?.let { " (${it.sdkCode})" } ?: ""}"))
        }
    }

    private suspend fun awaitGps(token: Long): KNGPSData = withTimeout(30000) {
        val result = CompletableDeferred<KNGPSData>()
        val receiver = object : KNGPSReceiver {
            override fun didReceiveGpsData(gpsData: KNGPSData) {
                if (token == operation && usable(gpsData)) result.complete(gpsData)
            }
        }
        try {
            KNSDK.requestLocationUpdate(receiver)
            KNSDK.sharedGpsManager()?.recentGpsData?.takeIf { usable(it) }?.let { result.complete(it) }
            result.await()
        } finally { KNSDK.removeLocationUpdate(receiver) }
    }

    private fun attach(active: KNDriveGuidance, token: Long) {
        active.locationGuideDelegate = object : KNGuidance_LocationGuideDelegate {
            override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
                if (token != operation) return
                location = locationGuide
                val gps = locationGuide.gpsOrigin
                if (usable(gps) && gps.timestamp.timeInMillis != lastGpsTimestamp) {
                    lastGpsTimestamp = gps.timestamp.timeInMillis
                    locationAtMillis = SystemClock.elapsedRealtime()
                }
                refresh()
            }
        }
        active.safetyGuideDelegate = object : KNGuidance_SafetyGuideDelegate {
            override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
                if (token != operation) return
                // aroundSafeties is deliberately not used: it may include unrelated roads.
                safeties = safetyGuide?.safetiesOnGuide.orEmpty()
                refresh()
            }
            override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) = Unit
        }
        active.voiceGuideDelegate = object : KNGuidance_VoiceGuideDelegate {
            override fun shouldPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice, sndData: MutableList<ByteArray>) = false
            override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) = Unit
            override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) = Unit
        }
        active.guideStateDelegate = object : KNGuidance_GuideStateDelegate {
            override fun guidanceGuideStarted(guidance: KNGuidance) = Unit
            override fun guidanceCheckingRouteChange(guidance: KNGuidance) = Unit
            override fun guidanceRouteUnchanged(guidance: KNGuidance) {
                if (token == operation) {
                    routeInvalid = false
                    publish(NavigationEvent.Mode(mode, "카카오 경로의 단속카메라를 안내합니다"))
                }
            }
            override fun guidanceRouteUnchangedWithError(guidance: KNGuidance, error: KNError) {
                invalidateRoute(token, "경로 갱신 실패 · 현재 위치를 확인합니다")
            }
            override fun guidanceOutOfRoute(guidance: KNGuidance) { invalidateRoute(token, "경로 이탈 · 카카오 경로를 다시 탐색합니다") }
            override fun guidanceRouteChanged(guidance: KNGuidance, oldRoute: KNRoute, oldLocation: KNLocation,
                newRoute: KNRoute, newLocation: KNLocation, changeReason: KNGuideRouteChangeReason) {
                invalidateRoute(token, "변경된 카카오 경로를 확인합니다")
                if (token == operation) {
                    expectedRoute = newRoute
                    routeInvalid = false
                    publish(NavigationEvent.Mode(mode, "카카오 경로의 단속카메라를 안내합니다"))
                }
            }
            override fun guidanceGuideEnded(guidance: KNGuidance) {
                if (token != operation) return
                clearRouteData()
                mode = NavigationMode.SAFETY
                routeInvalid = false
                active.startWithoutTrip()
                publish(NavigationEvent.Mode(mode, "목적지 도착 · 현재 도로 기준으로 안내합니다"))
            }
            override fun guidanceDidUpdateRoutes(guidance: KNGuidance, routes: List<KNRoute>, multiRouteInfo: KNMultiRouteInfo?) = Unit
            override fun guidanceDidUpdateIndoorRoute(guidance: KNGuidance, route: KNRoute?) = Unit
        }
    }

    private fun invalidateRoute(token: Long, message: String) {
        if (token != operation) return
        routeInvalid = true
        clearRouteData()
        publish(NavigationEvent.RouteInvalidated(message))
    }

    private fun clearRouteData() {
        revision++
        safeties = emptyList(); location = null; locationAtMillis = null; lastGpsTimestamp = null
        expectedRoute = null
    }

    private fun refresh() {
        val guide = location
        val gps = guide?.gpsOrigin
        val current = guide?.location
        val fresh = gps != null && usable(gps) && locationAtMillis?.let { SystemClock.elapsedRealtime() - it <= 10000 } == true
        val cameras = if (fresh && current != null && !routeInvalid) safeties.mapNotNull { safety ->
            val camera = safety as? KNSafety_Camera ?: return@mapNotNull null
            val kind = kind(camera.code) ?: return@mapNotNull null
            if (camera.passed) return@mapNotNull null
            if (mode == NavigationMode.ROUTE && (current.route == null || current.route != camera.location.route)) return@mapNotNull null
            if (mode == NavigationMode.ROUTE && expectedRoute != null && current.route != expectedRoute) return@mapNotNull null
            val distance = current.distToLocation(camera.location)
            if (distance !in 0..10000) return@mapNotNull null
            CameraReading("${camera.safetyId}:${camera.code.value}:${camera.location.pos.x.roundToInt()}:${camera.location.pos.y.roundToInt()}", kind, camera.speedLimit.takeIf { it > 0 },
                distance, camera.isVariableSpeedLimit)
        } else emptyList()
        publish(NavigationEvent.Frame(cameras, gps?.takeIf { it.speedTrust }?.speed,
            if (fresh && current != null && !routeInvalid) locationAtMillis else null, roadClass(current?.roadType),
            if (fresh) gps?.let { NavigationPosition(it.pos.x.toDouble(), it.pos.y.toDouble()) } else null))
    }

    private fun usable(gps: KNGPSData): Boolean = gps.valid && gps.posTrust &&
        kotlin.math.abs(System.currentTimeMillis() - gps.timestamp.timeInMillis) <= 10000 && gps.pos.x.isFinite() && gps.pos.y.isFinite()

    private fun publish(event: NavigationEvent) { updates.tryEmit(NavigationUpdate(revision, event)) }

    override fun pause() {
        operation++
        guidance?.let { detach(it); it.stop() }
        guidance = null
        clearRouteData()
    }
    private fun detach(active: KNDriveGuidance) {
        active.safetyGuideDelegate = null; active.locationGuideDelegate = null
        active.voiceGuideDelegate = null; active.guideStateDelegate = null
    }

    private class KakaoSdkException(val sdkCode: String) : IllegalStateException("카카오내비 오류 ($sdkCode). 앱 키·키 해시·SDK 이용 권한과 네트워크를 확인해 주세요.")
    private fun sdkError(error: KNError) = KakaoSdkException(error.code)

    companion object {
        internal fun roadClass(type: KNRoadType?): RoadClass = when (type) {
            KNRoadType.KNRoadType_Highway -> RoadClass.HIGHWAY
            KNRoadType.KNRoadType_GeneralRoad, KNRoadType.KNRoadType_NarrowRoad,
            KNRoadType.KNRoadType_Ferry, KNRoadType.KNRoadType_Indoor -> RoadClass.GENERAL
            else -> RoadClass.UNKNOWN
        }
        internal fun kind(code: KNSafetyCode): CameraKind? = when (code) {
            KNSafetyCode.KNSafetyCode_SpeedViolationCamera, KNSafetyCode.KNSafetyCode_BoxedSpeedViolationCamera -> CameraKind.SPEED
            KNSafetyCode.KNSafetyCode_SignalAndSpeedViolationCamera -> CameraKind.SIGNAL_SPEED
            KNSafetyCode.KNSafetyCode_MovableSpeedViolationCamera -> CameraKind.MOBILE
            KNSafetyCode.KNSafetyCode_SpeedViolationBackwardCamera -> CameraKind.REAR_SPEED
            KNSafetyCode.KNSafetyCode_SignalAndSpeedViolationBackwardCamera -> CameraKind.REAR_SIGNAL_SPEED
            KNSafetyCode.KNSafetyCode_BuslaneAndSpeedViolationCamera, KNSafetyCode.KNSafetyCode_LaneAndSpeedViolationCamera -> CameraKind.LANE_SPEED
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionInCamera,
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionInOnlyCamera,
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionInBackwardCamera,
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionInOnlyBackwardCamera -> CameraKind.SECTION_START
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionOutCamera,
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionOutOnlyCamera,
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionOutBackwardCamera,
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionOutOnlyBackwardCamera -> CameraKind.SECTION_END
            else -> null
        }
    }
}
