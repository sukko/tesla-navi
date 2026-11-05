package dev.teslanavi.drive

import android.app.*
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.teslanavi.MainActivity
import dev.teslanavi.R
import dev.teslanavi.TeslaNaviApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest

class DriveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private val app get() = application as TeslaNaviApp

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "차량 연결", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == OWNERSHIP) {
            if (session?.isActive == true) {
                if (AutoConnectService.running) stopForeground(STOP_FOREGROUND_REMOVE)
                else showSessionForeground()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == STOP || intent?.action == IDLE_STOP) {
            stopping = true
            val idleStop = intent.action == IDLE_STOP
            // Ending this trip is different from opting out of automatic connection for future trips.
            if (app.autoConnect.enabled.value) {
                app.drive.state.value.vin?.let { app.autoConnect.proximity.awaitDeparture(it, android.os.SystemClock.elapsedRealtime()) }
                if (!idleStop) app.drive.recordAutomaticStart("이번 연결 종료 · 자동 연결 설정 유지 · 차량에서 멀어진 뒤 재접근 대기")
                if (!idleStop && !AutoConnectService.running && AutoConnectSettings.ready(this)) {
                    try { AutoConnectService.start(this) }
                    catch (_: Exception) { app.autoConnect.report("자동 연결 설정은 유지됩니다. 앱에서 대기를 다시 시작해 주세요") }
                }
            }
            session?.cancel()
            app.drive.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val foregroundStart = intent?.getBooleanExtra(EXTRA_FOREGROUND_START, false) == true
        if (session?.isActive == true) {
            // A repeated startForegroundService() still obliges startForeground(); keep the running session's types.
            if (foregroundStart) {
                showSessionForeground()
                if (AutoConnectService.running) stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return START_NOT_STICKY
        }
        val automatic = intent?.getBooleanExtra("automatic", false) == true
        val profile = app.vehicles.vehicles.value.firstOrNull { it.vin == intent?.getStringExtra("vin") }
        if (profile == null) { app.drive.clear(); stopIgnoredStart(foregroundStart); return START_NOT_STICKY }
        if (automatic && (!app.autoConnect.enabled.value || profile.vin !in app.autoConnect.verified.value)) {
            app.drive.clear(); stopIgnoredStart(foregroundStart); return START_NOT_STICKY
        }
        if (automatic) app.vehicles.select(profile.vin)
        val nativeKey = app.kakao.nativeKey.orEmpty()
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val cameraEnabled = nativeKey.isNotBlank() && hasLocation
        try {
            if (!AutoConnectService.running) ServiceCompat.startForeground(this, 1, notification("차량 연결 준비 중"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    (if (cameraEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) else 0)
        } catch (error: Exception) { app.drive.fail(error); stopSelf(); return START_NOT_STICKY }
        busy = true
        stopping = false
        app.drive.prepare(profile.vin, automatic)
        val enroll = intent?.getBooleanExtra("enroll", false) == true
        session = scope.launch {
            val cameraSession = launch {
                if (nativeKey.isBlank()) app.cameras.configured(false)
                else if (!hasLocation) app.cameras.blocked("정확한 위치 권한을 허용한 뒤 차량에 다시 연결해 주세요")
                else app.cameras.run(nativeKey, app.drive.state)
            }
            val keepAwake = launch {
                val lock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TeslaNavi:VehicleSession")
                    .apply { setReferenceCounted(false) }
                wakeLock = lock
                try {
                    while (isActive) { lock.acquire(10 * 60 * 1000L); delay(5 * 60 * 1000L) }
                } finally { if (lock.isHeld) lock.release(); wakeLock = null }
            }
            val updates = launch {
                combine(app.drive.state, app.cameras.state) { drive, cameras ->
                    if (cameras.phoneFallback) "차량 연결 끊김 · 마지막 목적지로 카메라 안내 중"
                    else when (cameras.phase) {
                        dev.teslanavi.navigation.CameraPhase.WAITING_DRIVE -> "차량 연결됨 · 주행 시작 대기"
                        dev.teslanavi.navigation.CameraPhase.GUIDING -> "주행 중 · 카메라 안내"
                        else -> drive.status
                    }
                }.distinctUntilChanged().collect {
                    if (!AutoConnectService.running && !stopping) getSystemService(NotificationManager::class.java).notify(1, notification(it))
                }
            }
            val idleCleanup = launch {
                val idle = SessionIdleTimeout()
                combine(app.drive.state, app.cameras.state, app.bluetoothStart.state) { drive, cameras, bluetooth ->
                    idle.deadline(drive, cameras, bluetooth, android.os.SystemClock.elapsedRealtime())
                }.distinctUntilChanged().collectLatest { deadline ->
                    if (deadline != null && !AutoConnectService.running) {
                        delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        if (!AutoConnectService.running) stopForIdle(this@DriveService)
                    }
                }
            }
            // Keep observing P so departure after a long stop can restart guidance.
            try { runWithGuidanceRecovery(app.cameras.state,
                runVehicle = { app.drive.run(profile, enroll) }, onFailure = app.drive::fail,
                onRetry = {
                    app.drive.prepare(profile.vin, automatic)
                    app.drive.recordAutomaticStart("휴대폰 안내 유지 중 · 차량 재연결 시도")
                }) }
            catch (error: TimeoutCancellationException) { app.drive.fail(error) }
            catch (error: CancellationException) { app.drive.clear(); throw error }
            catch (error: Exception) { app.drive.fail(error) }
            finally {
                withContext(NonCancellable) {
                    cameraSession.cancelAndJoin()
                    keepAwake.cancel(); updates.cancel(); idleCleanup.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    if (automatic && app.drive.state.value.phase == DrivePhase.ERROR && profile.vin in app.autoConnect.verified.value) {
                        app.autoConnect.report(if (app.drive.state.value.asleep) "차량 잠듦 · 깨어나면 연결합니다"
                            else "연결 대기 · 차량이 가까워지면 다시 시도합니다")
                    }
                    busy = false
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Foreground notification for a running session; a failure leaves the session running as it was. */
    private fun showSessionForeground() {
        try {
            ServiceCompat.startForeground(this, 1, notification(if (app.cameras.state.value.phoneFallback)
                "차량 연결 끊김 · 마지막 목적지로 카메라 안내 중" else app.drive.state.value.status),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    (if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) else 0)
        } catch (error: Exception) {
            android.util.Log.w("TeslaNaviConnection", "Foreground notification failed: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Android kills the app when a service started with startForegroundService() stops before calling
     * startForeground(), even when the start is ignored. Satisfy that contract briefly, then leave.
     */
    private fun stopIgnoredStart(foregroundStart: Boolean) {
        if (foregroundStart) {
            try {
                ServiceCompat.startForeground(this, 1, notification("차량 연결 준비 중"),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            } catch (error: Exception) {
                android.util.Log.w("TeslaNaviConnection", "Ignored start could not enter foreground: ${error.javaClass.simpleName}")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, DriveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tesla Navi · 차량 연결").setContentText(message)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (app.autoConnect.enabled.value) "이번 연결 종료" else "연결 종료", stop).build()
    }

    override fun onDestroy() {
        scope.cancel()
        busy = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var busy = false
            private set
        private const val CHANNEL = "vehicle-connection"
        /** Set when the start used startForegroundService(), which obliges a startForeground() call. */
        private const val EXTRA_FOREGROUND_START = "foregroundStart"
        private const val OWNERSHIP = "dev.teslanavi.NOTIFICATION_OWNER"
        fun notificationOwnerChanged(context: Context) {
            context.startService(Intent(context, DriveService::class.java).setAction(OWNERSHIP))
        }
        private const val STOP = "dev.teslanavi.STOP"
        private const val IDLE_STOP = "dev.teslanavi.IDLE_STOP"
        internal fun stopForIdle(context: Context) { context.startService(Intent(context, DriveService::class.java).setAction(IDLE_STOP)) }
        fun start(context: Context, vin: String, enroll: Boolean, automatic: Boolean = false) {
            val intent = Intent(context, DriveService::class.java)
                .putExtra("vin", vin).putExtra("enroll", enroll).putExtra("automatic", automatic)
            if (AutoConnectService.running) context.startService(intent)
            else ContextCompat.startForegroundService(context, intent.putExtra(EXTRA_FOREGROUND_START, true))
        }
        fun stop(context: Context) { context.startService(Intent(context, DriveService::class.java).setAction(STOP)) }
    }
}
