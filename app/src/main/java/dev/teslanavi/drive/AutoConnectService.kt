package dev.teslanavi.drive

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.teslanavi.MainActivity
import dev.teslanavi.R
import dev.teslanavi.TeslaNaviApp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** A user-enabled, visible foreground service owns one filtered low-power [ProximityScan]. */
@SuppressLint("MissingPermission")
class AutoConnectService : Service() {
    private val app get() = application as TeslaNaviApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private val refreshRadio = Channel<Unit>(Channel.CONFLATED)
    private var quietStopping = false
    private var preserveScanOnStop = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "자동 차량 연결", NotificationManager.IMPORTANCE_MIN))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            disable(this)
            return START_NOT_STICKY
        }
        if (!app.autoConnect.enabled.value || !AutoConnectSettings.ready(this)) {
            app.autoConnect.report("자동 연결 권한을 확인해 주세요")
            ProximityScan.disarm(this)
            // start() always uses startForegroundService(); stopping before startForeground() crashes the app.
            // A sticky restart (null intent) carries no such obligation.
            if (intent != null) {
                try {
                    ServiceCompat.startForeground(this, NOTIFICATION, notification("자동 연결 권한을 확인해 주세요"),
                        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
                } catch (error: Exception) {
                    app.drive.recordAutomaticStart("대기 서비스 종료 알림 실패 · ${error.javaClass.simpleName}")
                }
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification("차량 접근을 기다리고 있습니다"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (error: Exception) {
            app.drive.recordAutomaticStart("알림 서비스 준비 실패 · ${error.javaClass.simpleName}")
            app.autoConnect.report("앱에서 자동 연결을 다시 켜 주세요")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        quietStopping = false
        preserveScanOnStop = false
        app.autoConnect.quiet(false)
        app.drive.recordAutomaticStart("대기 서비스 시작 완료")
        app.bluetoothStart.refresh()
        if (app.drive.state.value.active || DriveService.busy) DriveService.notificationOwnerChanged(this)
        if (monitor?.isActive == true) refreshRadio.trySend(Unit)
        if (monitor?.isActive != true) monitor = scope.launch {
            launch {
                combine(app.autoConnect.status, app.drive.state, app.cameras.state) { message, drive, cameras ->
                    if (cameras.phoneFallback) "차량 연결 끊김 · 마지막 목적지로 카메라 안내 중"
                    else if (!drive.active) message else when (cameras.phase) {
                        dev.teslanavi.navigation.CameraPhase.WAITING_DRIVE -> "차량 연결됨 · 주행 시작 대기"
                        dev.teslanavi.navigation.CameraPhase.GUIDING -> "주행 중 · 카메라 안내"
                        else -> drive.status
                    }
                }.distinctUntilChanged().collect { message ->
                    if (!quietStopping) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
                }
            }
            launch {
                val idle = SessionIdleTimeout()
                combine(app.drive.state, app.cameras.state, app.bluetoothStart.state) { drive, cameras, bluetooth ->
                    idle.deadline(drive, cameras, bluetooth, SystemClock.elapsedRealtime())
                }.distinctUntilChanged().collectLatest { deadline ->
                    if (deadline != null) {
                        delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        while (isActive && !quietStopping) {
                            if (stopIdleWaiting()) break
                            delay(60_000) // Keep the visible service if a reliable wake-up scan could not be installed.
                        }
                    }
                }
            }
            val radio = radioChanges().distinctUntilChanged()
            combine(app.vehicles.vehicles, app.autoConnect.verified, app.autoConnect.enabled, radio) { profiles, verified, enabled, ready ->
                MonitorTarget(profiles.map { it.vin }.toSet(), verified, enabled, ready.permissions, ready.bluetooth, ready.location,
                    radioRevision = ready.revision)
            }.combine(app.bluetoothStart.state) { target, bluetooth ->
                target.copy(triggerAllowed = bluetooth.allowed, triggerName = bluetooth.selectedNames.takeIf { it.isNotEmpty() })
            }.combine(app.drive.state) { target, drive ->
                target.copy(vehicleActive = drive.active)
            }.distinctUntilChanged().collectLatest { target ->
                app.autoConnect.proximity.update(target.registered, target.verified)
                when {
                    !target.enabled -> stopSelf()
                    !target.permissions -> app.autoConnect.report("자동 연결 권한을 확인해 주세요")
                    !target.bluetooth -> app.autoConnect.report("Bluetooth를 켜면 자동 연결을 다시 시작합니다")
                    !target.location -> app.autoConnect.report("휴대폰 위치 기능을 켜면 자동 연결을 다시 시작합니다")
                    target.vehicleActive -> app.autoConnect.report("차량 연결 중 · 추가 차량 검색 대기")
                    !target.triggerAllowed -> {
                        app.drive.recordAutomaticStart("선택 기기 연결 대기")
                        app.autoConnect.report("${target.triggerName ?: "선택한 블루투스 기기"} 연결 대기 · 연결되면 차량을 검색합니다")
                    }
                    target.registered.isEmpty() -> app.autoConnect.report("자동 연결할 차량을 등록해 주세요")
                    app.autoConnect.proximity.scanNames.isEmpty() -> app.autoConnect.report("먼저 차량에 앱 키를 등록하고 한 번 연결해 주세요")
                    else -> {
                        while (currentCoroutineContext().isActive) {
                            try { scan(app.autoConnect.proximity.scanNames) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) {
                                app.autoConnect.report("차량 검색을 다시 준비하고 있습니다")
                                delay(60_000)
                            }
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    /** Polling alone misses an off/on cycle between polls: Android has discarded the old scan. */
    private fun radioChanges() = callbackFlow {
        var revision = 0
        fun sample(bluetoothOverride: Boolean? = null) {
            val permissions = AutoConnectSettings.ready(this@AutoConnectService)
            val bluetooth = permissions && (bluetoothOverride ?: runCatching {
                getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            }.getOrDefault(false))
            val location = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))
            if (bluetooth) app.bluetoothStart.refresh()
            trySend(RadioState(permissions, bluetooth, location, revision))
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                            BluetoothAdapter.STATE_ON -> {
                                // A revision also restores the scan if off/on events were coalesced downstream.
                                revision++
                                app.drive.recordAutomaticStart("Bluetooth 켜짐 · 차량 검색 복원")
                                sample(true)
                            }
                            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> sample(false)
                        }
                    }
                    LocationManager.MODE_CHANGED_ACTION -> sample()
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(LocationManager.MODE_CHANGED_ACTION) }
        // Bluetooth broadcasts may originate from a separate privileged UID. These are protected actions.
        ContextCompat.registerReceiver(this@AutoConnectService, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        sample()
        val repair = launch { while (isActive) { delay(30_000); sample() } }
        val refresh = launch { for (ignored in refreshRadio) { revision++; sample() } }
        awaitClose { repair.cancel(); refresh.cancel(); unregisterReceiver(receiver) }
    }

    // Leaving this function stops the scan; a killed process skips the finally block and keeps it.
    private suspend fun scan(names: Set<String>) {
        try {
            app.drive.recordAutomaticStart("블루투스 조건 충족 · 차량 BLE 검색 시작")
            app.autoConnect.report("등록 차량 ${names.size}대 접근 대기 · 먼저 감지된 차량에 연결")
            ProximityScan.arm(this, names)
            error("BLE 검색 오류 ${ProximityScan.failures.first()}")
        } finally { if (!preserveScanOnStop) ProximityScan.disarm(this) }
    }

    /** Remove the foreground service rather than hiding a notification while keeping long-running work alive. */
    private fun stopIdleWaiting(): Boolean {
        if (app.bluetoothStart.state.value.selectedDevices.isEmpty()) {
            val names = app.autoConnect.proximity.scanNames
            val bluetoothOn = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
            if (names.isNotEmpty() && bluetoothOn && AutoConnectSettings.ready(this)) {
                if (runCatching { ProximityScan.arm(this, names) }.isFailure) return false
                preserveScanOnStop = true // Android owns this PendingIntent scan after the service exits.
            }
        }
        quietStopping = true
        app.autoConnect.quiet(true)
        app.drive.recordAutomaticStart("하차·연결 대기 2분 · 상시 알림 해제 · 자동 연결 설정 유지")
        if (app.drive.state.value.active || DriveService.busy) DriveService.stopForIdle(this)
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        stopSelf()
        return true
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 11, Intent(this, AutoConnectService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tesla Navi · 자동 연결").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, "자동 연결 끄기", stop).build()
    }

    override fun onDestroy() { running = false; scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private data class RadioState(val permissions: Boolean, val bluetooth: Boolean, val location: Boolean, val revision: Int)
    private data class MonitorTarget(val registered: Set<String>, val verified: Set<String>, val enabled: Boolean, val permissions: Boolean, val bluetooth: Boolean, val location: Boolean, val triggerAllowed: Boolean = true, val triggerName: String? = null, val vehicleActive: Boolean = false, val radioRevision: Int = 0)

    companion object {
        @Volatile var running = false
            private set
        private const val CHANNEL = "auto-connection-quiet"
        private const val NOTIFICATION = 2
        private const val STOP = "dev.teslanavi.AUTO_STOP"
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AutoConnectService::class.java))
        }
        /** Called on the main thread for each advertisement that [ProximityScanReceiver] delivers. */
        fun onAdvertisement(context: Context, name: String?, rssi: Int, connectable: Boolean) {
            val app = context.applicationContext as TeslaNaviApp
            if (!app.autoConnect.enabled.value || !app.bluetoothStart.state.value.allowed) return
            // Recheck eligibility because deliveries from an older scan registration may still be queued.
            app.autoConnect.proximity.update(app.vehicles.vehicles.value.map { it.vin }.toSet(), app.autoConnect.verified.value)
            val vin = app.autoConnect.proximity.observe(name, rssi, connectable,
                SystemClock.elapsedRealtime(), busy = app.drive.state.value.active || DriveService.busy) ?: return
            app.vehicles.select(vin)
            app.autoConnect.report("등록 차량을 발견해 연결하고 있습니다")
            app.drive.prepare(vin, automatic = true)
            try { DriveService.start(context, vin, enroll = false, automatic = true) }
            catch (error: Exception) { app.drive.fail(error); app.autoConnect.report("앱에서 자동 연결을 다시 켜 주세요") }
        }
        fun disable(context: Context) {
            val app = context.applicationContext as TeslaNaviApp
            app.drive.recordAutomaticStart("사용자 요청 · 자동 연결 끄기")
            app.autoConnect.setEnabled(false)
            ProximityScan.disarm(context)
            running = false
            if ((app.drive.state.value.active || DriveService.busy) && !app.drive.state.value.automatic) DriveService.notificationOwnerChanged(context)
            context.stopService(Intent(context, AutoConnectService::class.java))
            if (app.drive.state.value.automatic && (app.drive.state.value.active || DriveService.busy)) DriveService.stop(context)
        }
    }
}
