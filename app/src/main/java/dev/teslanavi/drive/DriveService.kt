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

class DriveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
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
                else ServiceCompat.startForeground(this, 1, notification(if (app.cameras.state.value.phoneFallback)
                    "차량 연결 끊김 · 마지막 목적지로 카메라 안내 중" else app.drive.state.value.status),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        (if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) else 0)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            app.autoConnect.setEnabled(false)
            stopService(Intent(this, AutoConnectService::class.java))
            session?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (session?.isActive == true) return START_NOT_STICKY
        val automatic = intent?.getBooleanExtra("automatic", false) == true
        val profile = app.vehicles.vehicles.value.firstOrNull { it.vin == intent?.getStringExtra("vin") }
        if (profile == null) { app.drive.clear(); stopSelf(); return START_NOT_STICKY }
        if (automatic && (!app.autoConnect.enabled.value || profile.vin !in app.autoConnect.verified.value)) {
            app.drive.clear(); stopSelf(); return START_NOT_STICKY
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
                    if (!AutoConnectService.running) getSystemService(NotificationManager::class.java).notify(1, notification(it))
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
                    keepAwake.cancel(); updates.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
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

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, DriveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tesla Navi · 차량 연결").setContentText(message)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (app.autoConnect.enabled.value) "연결·자동 연결 종료" else "연결 종료", stop).build()
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
        private const val OWNERSHIP = "dev.teslanavi.NOTIFICATION_OWNER"
        fun notificationOwnerChanged(context: Context) {
            context.startService(Intent(context, DriveService::class.java).setAction(OWNERSHIP))
        }
        private const val STOP = "dev.teslanavi.STOP"
        fun start(context: Context, vin: String, enroll: Boolean, automatic: Boolean = false) {
            val intent = Intent(context, DriveService::class.java)
                .putExtra("vin", vin).putExtra("enroll", enroll).putExtra("automatic", automatic)
            if (AutoConnectService.running) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) { context.startService(Intent(context, DriveService::class.java).setAction(STOP)) }
    }
}
