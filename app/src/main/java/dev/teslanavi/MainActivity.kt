package dev.teslanavi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import dev.teslanavi.drive.BluetoothStartState
import dev.teslanavi.drive.StartBluetoothDevice
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.teslanavi.data.BatteryReading
import dev.teslanavi.data.VehicleProfile
import dev.teslanavi.data.batteryAge
import dev.teslanavi.drive.DrivePhase
import dev.teslanavi.drive.DriveService
import dev.teslanavi.drive.DriveState
import dev.teslanavi.drive.AutoConnectService
import dev.teslanavi.drive.AutoConnectSettings
import dev.teslanavi.drive.BatteryPoll
import dev.teslanavi.logbook.*
import dev.teslanavi.protocol.NavigationDestination
import dev.teslanavi.protocol.VehicleIdentity
import dev.teslanavi.navigation.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val Ink = Color(0xFF101419)
private val Panel = Color(0xFF1A2028)
private val Mint = Color(0xFF6EE7CF)
private val Muted = Color(0xFFA2AEBD)
private val NightColors = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink,
    surface = Panel, onSurface = Color(0xFFF1F5F9), surfaceVariant = Color(0xFF242D38), onSurfaceVariant = Muted)

class MainActivity : ComponentActivity() {
    private val app get() = application as TeslaNaviApp
    private var pendingEnroll = false
    private var pendingVin: String? = null
    private var pendingAutomatic = false
    private val backgroundDisclosure = mutableStateOf(false)
    private val localError = mutableStateOf<String?>(null)
    private val showBluetooth = mutableStateOf(false)
    private val bluetoothListPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        app.bluetoothStart.refresh()
        showBluetooth.value = true
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingAutomatic && (!granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)))) {
            localError.value = "자동 연결에는 정확한 위치와 알림 권한이 필요합니다. 권한을 허용한 뒤 다시 켜 주세요."
            pendingAutomatic = false
        } else if (bluetoothPermissions().all { granted(it) }) enableBluetoothAndStart()
        else localError.value = "차량 검색을 위해 근처 기기와 정확한 위치 권한을 허용해 주세요."
    }
    private val bluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) continueStart()
        else localError.value = "Bluetooth를 켜야 차량에 연결할 수 있습니다."
    }
    private val backgroundPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) activateAutomatic() else { pendingAutomatic = false; localError.value = "자동 연결을 켜려면 위치 권한을 항상 허용해 주세요." }
    }
    private val backgroundSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingAutomatic && AutoConnectSettings.ready(this)) activateAutomatic()
        else { pendingAutomatic = false; localError.value = "자동 연결을 켜려면 위치 권한을 항상 허용해 주세요." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAutomatic = savedInstanceState?.getBoolean("pending-automatic") ?: false
        pendingVin = savedInstanceState?.getString("pending-vin")
        pendingEnroll = savedInstanceState?.getBoolean("pending-enroll") ?: false
        setContent {
            MaterialTheme(colorScheme = NightColors) {
                val profiles by app.vehicles.vehicles.collectAsStateWithLifecycle()
                val selected by app.vehicles.selected.collectAsStateWithLifecycle()
                val state by app.drive.state.collectAsStateWithLifecycle()
                val cameras by app.cameras.state.collectAsStateWithLifecycle()
                val hasKakaoKey by app.kakao.hasKey.collectAsStateWithLifecycle()
                val autoEnabled by app.autoConnect.enabled.collectAsStateWithLifecycle()
                val autoStatus by app.autoConnect.status.collectAsStateWithLifecycle()
                val bluetoothStart by app.bluetoothStart.state.collectAsStateWithLifecycle()
                val guidanceOptions by app.guidance.options.collectAsStateWithLifecycle()
                val settingsTab by app.features.settingsTab.collectAsStateWithLifecycle()
                val batteries by app.drive.battery.collectAsStateWithLifecycle()
                val logbook by app.features.logbook.collectAsStateWithLifecycle()
                val wonPerKwh by app.logbookSettings.wonPerKwh.collectAsStateWithLifecycle()
                val chargeEntries by app.logbook.entries.collectAsStateWithLifecycle()
                val trips by app.trips.trips.collectAsStateWithLifecycle()
                var showGuidance by remember { mutableStateOf(false) }
                var logbookVin by rememberSaveable { mutableStateOf<String?>(null) }
                val guidanceActive = cameras.phase in setOf(CameraPhase.INITIALIZING, CameraPhase.WAITING_GPS, CameraPhase.GUIDING)
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    VehicleScreen(profiles, selected, state, selected?.let { batteries[it] }, cameras, hasKakaoKey, localError.value, autoEnabled, autoStatus, guidanceOptions,
                        onSettings = { showGuidance = true },
                        onAdd = { model, vin -> app.vehicles.add(model, vin); app.drive.clear(); localError.value = null },
                        onSelect = { app.vehicles.select(it); app.drive.clear(); localError.value = null },
                        onConnect = { enroll -> begin(enroll) },
                        onDisconnect = { DriveService.stop(this) },
                        onRefresh = { app.drive.requestRefresh() },
                        onAutomatic = { enabled -> if (enabled) beginAutomatic() else AutoConnectService.disable(this) },
                        onMute = { app.cameras.setMuted(it) },
                        bluetoothStart = bluetoothStart, onBluetooth = {
                            if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
                                bluetoothListPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            else { app.bluetoothStart.refresh(); showBluetooth.value = true }
                        },
                        settingsTab = settingsTab, onSettingsTab = { app.features.setSettingsTab(it) },
                        logbook = logbook, onLogbook = { app.features.setLogbook(it) },
                        guidancePage = { onBack -> GuidanceSettingsPage(app, guidanceActive, onBack) }) { onOpenSettings ->
                        // The logbook picks its own vehicle; it defaults to the connection selection but never changes it.
                        val profile = profiles.firstOrNull { it.vin == logbookVin } ?: profiles.firstOrNull { it.vin == selected } ?: profiles.firstOrNull()
                        StatsScreen(state, onOpenSettings, profiles, profile, chargeEntries[profile?.vin].orEmpty(), trips[profile?.vin].orEmpty(),
                            wonPerKwh, state.takeIf { it.vin != null && it.vin == profile?.vin }?.snapshot?.odometerKm,
                            onVehicle = { logbookVin = it }, onRate = { app.logbookSettings.rate(it) },
                            onSetRate = { id, rate -> profile?.let { app.logbook.setRate(it.vin, id, rate) } },
                            onAdd = { odometer, energy -> app.logbook.add(checkNotNull(profile).vin, odometer, energy) },
                            onDelete = { id -> profile?.let { app.logbook.delete(it.vin, id) } },
                            onDeleteTrip = { id -> profile?.let { app.trips.delete(it.vin, id) } })
                    }
                }
                if (showGuidance) GuidanceSettingsDialog(app, guidanceActive) { showGuidance = false }
                if (showBluetooth.value) BluetoothStartDialog(bluetoothStart,
                    onRefresh = { app.bluetoothStart.refresh() },
                    onSystemSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    onSelect = { devices ->
                        app.bluetoothStart.select(devices)
                        showBluetooth.value = false
                        if (devices.isNotEmpty()) beginAutomatic()
                    }, onDismiss = { showBluetooth.value = false })
                if (backgroundDisclosure.value) AlertDialog(
                    onDismissRequest = { backgroundDisclosure.value = false; pendingAutomatic = false },
                    title = { Text("자동 연결에 필요한 위치 권한") },
                    text = { Text("화면이 꺼져 있거나 휴대폰을 재시작한 뒤에도 차량에 연결하고 카메라 안내를 시작하려면 위치 권한의 ‘항상 허용’이 필요합니다. 다음 설정 화면에서 권한 → 위치 → 항상 허용을 선택해 주세요.") },
                    confirmButton = { TextButton(onClick = {
                        backgroundDisclosure.value = false
                        backgroundSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }) { Text("권한 설정 열기") } },
                    dismissButton = { TextButton(onClick = { backgroundDisclosure.value = false; pendingAutomatic = false }) { Text("취소") } })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending-automatic", pendingAutomatic)
        outState.putString("pending-vin", pendingVin)
        outState.putBoolean("pending-enroll", pendingEnroll)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        app.bluetoothStart.refresh()
        if (app.autoConnect.enabled.value && AutoConnectSettings.ready(this)) {
            try { AutoConnectService.start(this) } catch (_: Exception) { app.autoConnect.report("자동 연결을 다시 켜 주세요") }
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun bluetoothPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun begin(enroll: Boolean) {
        if (app.drive.state.value.active) return
        localError.value = null
        pendingVin = app.vehicles.selected.value ?: return
        pendingEnroll = enroll
        pendingAutomatic = false
        val required = bluetoothPermissions().filterNot { granted(it) }.toMutableList()
        if (app.kakao.hasKey.value) {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .filterNot { granted(it) }.forEach { if (it !in required) required += it }
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) required += Manifest.permission.POST_NOTIFICATIONS
        if (required.isNotEmpty()) permissionLauncher.launch(required.toTypedArray()) else enableBluetoothAndStart()
    }

    private fun beginAutomatic() {
        pendingVin = app.vehicles.selected.value ?: return
        pendingAutomatic = true
        localError.value = null
        val required = (bluetoothPermissions() + listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).distinct().filterNot { granted(it) }
        if (required.isNotEmpty()) permissionLauncher.launch(required.toTypedArray()) else enableBluetoothAndStart()
    }

    private fun continueStart() {
        if (!pendingAutomatic) { startSession(); return }
        when {
            Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> activateAutomatic()
            Build.VERSION.SDK_INT == 29 -> backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> backgroundDisclosure.value = true
        }
    }

    private fun activateAutomatic() {
        pendingAutomatic = false
        if (!AutoConnectSettings.ready(this)) { localError.value = "자동 연결 권한을 확인해 주세요."; return }
        try {
            app.autoConnect.setEnabled(true)
            AutoConnectService.start(this)
        } catch (_: Exception) { app.autoConnect.setEnabled(false); localError.value = "자동 연결을 시작하지 못했습니다. 다시 켜 주세요." }
    }

    private fun enableBluetoothAndStart() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { localError.value = "이 기기는 Bluetooth LE를 지원하지 않습니다."; return }
        if (!adapter.isEnabled) bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) else continueStart()
    }

    private fun startSession() {
        val vin = pendingVin ?: return
        if (app.drive.state.value.active || app.vehicles.selected.value != vin) return
        try {
            app.drive.prepare(vin)
            DriveService.start(this, vin, pendingEnroll)
        } catch (error: Exception) { app.drive.fail(error) }
    }
}

private enum class AppTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    MAIN("메인", Icons.Outlined.Home, Icons.Filled.Home),
    LOGBOOK("통계", BarChartOutlined, BarChartFilled),
    SETTINGS("설정", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
private fun BluetoothStartDialog(state: BluetoothStartState, onRefresh: () -> Unit,
    onSystemSettings: () -> Unit, onSelect: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(state.selectedDevices) }
    val devices = state.devices + state.selectedDevices.filterKeys { address -> state.devices.none { it.address == address } }
        .map { (address, name) -> StartBluetoothDevice(address, name, false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("블루투스 자동 시작") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("휴대폰에 등록된 기기를 여러 개 선택할 수 있습니다. 선택한 기기 중 하나라도 연결되면 차량을 검색하고 주행 시작 후 안내합니다.")
                Text("자동 연결 스위치가 켜져 있어야 합니다. 앱 화면은 알림을 눌러 열 수 있습니다.", color = Muted, fontSize = 12.sp)
                Text("선택한 기기 ${selected.size}개", color = Mint)
                state.message?.let { Text(it, color = Muted) }
                devices.forEach { device ->
                    Row(Modifier.fillMaxWidth().toggleable(value = device.address in selected, role = Role.Checkbox,
                        onValueChange = { checked -> selected = if (checked) selected + (device.address to device.name) else selected - device.address })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = device.address in selected, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(device.name)
                            Text("${if (device.connected) "연결됨" else "등록됨 · 연결 안 됨"} · ${device.address.takeLast(5)}", fontSize = 11.sp)
                        }
                    }
                }
                TextButton(onClick = { selected = emptyMap() }) { Text("전체 선택 해제 · 차량 접근으로 시작") }
                TextButton(onClick = onRefresh) { Text("목록 새로고침") }
                TextButton(onClick = onSystemSettings) { Text("휴대폰 Bluetooth 설정") }
            }
        }, confirmButton = { TextButton(onClick = { onSelect(selected) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun VehicleScreen(profiles: List<VehicleProfile>, selected: String?, state: DriveState, battery: BatteryReading?,
    cameras: CameraState, hasKakaoKey: Boolean, localError: String?, autoEnabled: Boolean, autoStatus: String, options: GuidanceOptions, onSettings: () -> Unit,
    onAdd: (String, String) -> Unit, onSelect: (String) -> Unit, onConnect: (Boolean) -> Unit,
    onDisconnect: () -> Unit, onRefresh: () -> Unit, onAutomatic: (Boolean) -> Unit, onMute: (Boolean) -> Unit,
    bluetoothStart: BluetoothStartState, onBluetooth: () -> Unit,
    settingsTab: Boolean, onSettingsTab: (Boolean) -> Unit, logbook: Boolean, onLogbook: (Boolean) -> Unit,
    guidancePage: @Composable (onBack: () -> Unit) -> Unit,
    logbookScreen: @Composable (onOpenSettings: (() -> Unit)?) -> Unit) {
    // Without the settings tab feature, the main screen keeps every control and the top-right button opens the feature switches.
    var chosen by rememberSaveable { mutableStateOf(AppTab.MAIN) }
    // Inside the settings tab, guidance settings open as a page one level down instead of the main screen's dialog.
    var guidanceOpen by rememberSaveable { mutableStateOf(false) }
    var showFeatures by remember { mutableStateOf(false) }
    val tabs = listOfNotNull(AppTab.MAIN, AppTab.LOGBOOK.takeIf { logbook }, AppTab.SETTINGS.takeIf { settingsTab })
    val tab = chosen.takeIf { it in tabs } ?: AppTab.MAIN
    val connected = state.phase == DrivePhase.CONNECTED
    val batteryLive = connected && state.vin == selected
    BackHandler(enabled = tab != AppTab.MAIN) { chosen = AppTab.MAIN }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when {
                tab == AppTab.LOGBOOK -> logbookScreen(if (settingsTab) null else ({ showFeatures = true }))
                !settingsTab -> ScreenColumn {
                    MainHeader(state, onOpenSettings = { showFeatures = true })
                    battery?.let { BatteryCard(it, batteryLive) }
                    GuidanceButton(onSettings)
                    if (connected) CameraCard(cameras, hasKakaoKey, onMute, options, onDisconnect)
                    VehicleList(profiles, selected, state, onAdd, onSelect)
                    AutoConnectCard(profiles, autoEnabled, autoStatus, onAutomatic, bluetoothStart, onBluetooth)
                    ConnectionCard(profiles, selected, state, localError, autoEnabled, onConnect, onDisconnect)
                    DestinationCard(selected, state, onRefresh)
                    if (!connected) CameraCard(cameras, hasKakaoKey, onMute, options, onDisconnect)
                    Diagnostics(selected, state)
                    VersionFooter()
                }
                tab == AppTab.MAIN -> ScreenColumn {
                    MainHeader(state, onOpenSettings = null)
                    battery?.let { BatteryCard(it, batteryLive) }
                    if (connected) CameraCard(cameras, hasKakaoKey, onMute, options, onDisconnect)
                    else StatusCard(profiles, selected, state, localError) { chosen = AppTab.SETTINGS }
                    DestinationCard(selected, state, onRefresh)
                    if (!connected) CameraCard(cameras, hasKakaoKey, onMute, options, onDisconnect)
                }
                guidanceOpen -> guidancePage { guidanceOpen = false }
                else -> ScreenColumn {
                    Text("설정", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    AppCard {
                        Text("기능", color = Muted, fontSize = 13.sp)
                        FeatureSwitches(settingsTab, onSettingsTab, logbook, onLogbook)
                    }
                    VehicleList(profiles, selected, state, onAdd, onSelect)
                    ConnectionCard(profiles, selected, state, localError, autoEnabled, onConnect, onDisconnect)
                    AutoConnectCard(profiles, autoEnabled, autoStatus, onAutomatic, bluetoothStart, onBluetooth)
                    GuidanceCard(options) { guidanceOpen = true }
                    Diagnostics(selected, state)
                    VersionFooter()
                }
            }
        }
        if (tabs.size > 1) TabBar(tabs, tab) { chosen = it; guidanceOpen = false }
    }
    if (showFeatures) AlertDialog(onDismissRequest = { showFeatures = false },
        title = { Text("설정") },
        text = { FeatureSwitches(settingsTab, onSettingsTab, logbook, onLogbook) },
        confirmButton = { TextButton(onClick = { showFeatures = false }) { Text("닫기") } })
}

@Composable
private fun FeatureSwitches(settingsTab: Boolean, onSettingsTab: (Boolean) -> Unit, logbook: Boolean, onLogbook: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FeatureSwitch("하단 설정 탭", "켜면 화면 아래에 설정 탭이 생기고 차량 등록·연결, 자동 연결, 안내 설정, 연결 기록을 설정 탭으로 옮깁니다. 끄면 메인 화면에 다시 표시합니다.",
            settingsTab, onSettingsTab)
        FeatureSwitch("통계 탭", "켜면 화면 아래에 통계 탭이 생깁니다. 충전 기록으로 전비와 km당 충전비를, 주행 기록으로 거리와 시간을 보여 주며, 차량이 연결된 동안 자동으로 기록합니다.",
            logbook, onLogbook)
    }
}

@Composable
private fun FeatureSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun TabBar(tabs: List<AppTab>, selected: AppTab, onSelect: (AppTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ink).navigationBarsPadding()) {
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFF242D38))
        Row(Modifier.fillMaxWidth().height(50.dp).selectableGroup()) {
            tabs.forEach { tab ->
                val active = tab == selected
                Box(Modifier.weight(1f).fillMaxHeight().selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }),
                    contentAlignment = Alignment.Center) {
                    Icon(if (active) tab.selectedIcon else tab.icon, contentDescription = tab.label,
                        tint = if (active) Mint else Muted, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}

@Composable
private fun MainHeader(state: DriveState, onOpenSettings: (() -> Unit)?, title: String = "내 차의 목적지") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("TESLA NAVI", letterSpacing = 3.sp, fontSize = 12.sp, color = Mint, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        Surface(color = Color(0xFF243039), shape = RoundedCornerShape(50)) {
            Text(if (state.phase == DrivePhase.CONNECTED) "● 연결됨" else "● 대기", Modifier.padding(12.dp, 8.dp), color = Mint, fontSize = 12.sp)
        }
        if (onOpenSettings != null) IconButton(onClick = onOpenSettings, modifier = Modifier.offset(x = 8.dp)) {
            Icon(Icons.Outlined.Settings, contentDescription = "설정 열기", modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun BatteryCard(reading: BatteryReading, connected: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }
    val minutes = (now - reading.receivedAt).coerceAtLeast(0) / 60_000
    // While disconnected, or once two scheduled reads are missed, keep the last value but mark it as not live.
    val live = connected && minutes < 2 * BatteryPoll.INTERVAL / 60_000
    val color = if (live) Mint else MaterialTheme.colorScheme.onSurface
    Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = reading.summary },
        shape = RoundedCornerShape(22.dp), color = Panel) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                reading.levelPercent?.let { BatteryValue("배터리", "$it%", color) }
                reading.rangeKm?.let { BatteryValue("주행 가능", "${it.roundToInt()} km", color) }
            }
            Text(if (live) "${batteryAge(minutes)} 확인 · ${BatteryPoll.INTERVAL / 60_000}분마다 갱신" else "마지막 수신 ${batteryAge(minutes)}", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BatteryValue(label: String, value: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun GuidanceButton(onSettings: () -> Unit) {
    Button(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
        Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text("안내 설정", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GuidanceCard(options: GuidanceOptions, onSettings: () -> Unit) {
    AppCard {
        Text("안내", color = Muted, fontSize = 13.sp)
        Text("음량 ${options.volumePercent}% · 고속도로 ${options.highwayMeters} m · 일반도로 ${options.generalMeters} m 전", fontSize = 15.sp)
        FilledTonalButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("안내 설정", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusCard(profiles: List<VehicleProfile>, selected: String?, state: DriveState, localError: String?, onOpenSettings: () -> Unit) {
    val current = state.takeIf { it.vin == selected }
    AppCard {
        Text("차량 연결", color = Muted, fontSize = 13.sp)
        if (state.active) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
        Text(if (profiles.isEmpty()) "차량을 등록해 주세요" else current?.status ?: "차량을 연결해 주세요", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        val error = localError ?: current?.error
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        TextButton(onClick = onOpenSettings) { Text(if (profiles.isEmpty()) "설정에서 차량 등록" else "차량 설정 열기") }
    }
}

@Composable
private fun VehicleList(profiles: List<VehicleProfile>, selected: String?, state: DriveState,
    onAdd: (String, String) -> Unit, onSelect: (String) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("내 차량", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { showAdd = true }, enabled = !state.active) { Text("+ 차량 등록") }
        }
        if (profiles.isEmpty()) {
            AppCard {
                Text("첫 차량을 등록해 주세요", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("Model 3 또는 Model Y의 차대번호(VIN)로 연결할 차량을 찾습니다.", color = Muted)
                FilledTonalButton(onClick = { showAdd = true }) { Text("차량 등록하기") }
            }
        } else {
            profiles.forEach { profile ->
                OutlinedButton(onClick = { onSelect(profile.vin) }, enabled = !state.active,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (profile.vin == selected) Color(0xFF203732) else Color.Transparent,
                        contentColor = if (profile.vin == selected) Mint else Muted,
                        disabledContainerColor = if (profile.vin == selected) Color(0xFF203732) else Color.Transparent,
                        disabledContentColor = if (profile.vin == selected) Mint else Muted)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(profile.model, fontWeight = FontWeight.SemiBold)
                        Text("••• ${profile.vin.takeLast(6)}${if (profile.vin == selected) "  ✓" else ""}")
                    }
                }
            }
        }
    }
    if (showAdd) VehicleDialog(onDismiss = { showAdd = false }, onSave = { model, vin -> onAdd(model, vin); showAdd = false })
}

@Composable
private fun AutoConnectCard(profiles: List<VehicleProfile>, autoEnabled: Boolean, autoStatus: String, onAutomatic: (Boolean) -> Unit, bluetoothStart: BluetoothStartState, onBluetooth: () -> Unit) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("차량 접근 시 자동 연결", fontWeight = FontWeight.SemiBold)
                Text("연결 → 주행 시작 → 카메라 안내", color = Muted, fontSize = 12.sp)
            }
            Switch(checked = autoEnabled, onCheckedChange = onAutomatic, enabled = profiles.isNotEmpty(),
                modifier = Modifier.semantics { contentDescription = "자동 연결" })
        }
        Text(autoStatus, color = Muted, fontSize = 13.sp)
        Text("키 인증을 마친 등록 차량 중 먼저 감지된 차량에 자동 연결합니다. 각 차량은 처음 한 번 앱 키 등록과 차량 연결이 필요합니다.", color = Muted, fontSize = 11.sp)
            OutlinedButton(onClick = onBluetooth, modifier = Modifier.fillMaxWidth()) { Text("블루투스 자동 시작 설정") }
            Text(if (bluetoothStart.selectedDevices.isNotEmpty()) "선택한 기기 ${bluetoothStart.selectedDevices.size}개 중 하나 연결 시 자동 시작 · ${if (bluetoothStart.allowed) "기기 연결됨" else "연결 대기"}"
                else "블루투스 기기를 선택하면 해당 기기 연결 시 차량을 검색합니다.", color = Muted, fontSize = 12.sp)
        if (autoEnabled) TextButton(onClick = { onAutomatic(true) }) { Text("권한 확인 · 대기 재시작") }
    }
}

@Composable
private fun ConnectionCard(profiles: List<VehicleProfile>, selected: String?, state: DriveState, localError: String?,
    autoEnabled: Boolean, onConnect: (Boolean) -> Unit, onDisconnect: () -> Unit) {
    var showEnroll by remember { mutableStateOf(false) }
    val activeProfile = profiles.firstOrNull { it.vin == selected }
    val current = state.takeIf { it.vin == selected }
    AppCard {
        Text("차량 연결", color = Muted, fontSize = 13.sp)
        if (state.active && state.phase != DrivePhase.CONNECTED) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
        Text(current?.status ?: "차량을 연결해 주세요", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        if (state.phase == DrivePhase.PAIRING) Text("키 카드를 센터 콘솔의 카드 리더에 대고 차량 화면의 안내에 따라 승인해 주세요. 최대 90초 동안 확인합니다.", color = Muted)
        if (state.active) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text(if (autoEnabled) "연결 종료 · 자동 연결 끄기" else "연결 종료") }
        } else {
            Button(onClick = { onConnect(false) }, enabled = activeProfile != null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("차량 연결", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { showEnroll = true }, enabled = activeProfile != null, modifier = Modifier.fillMaxWidth()) { Text("처음 연결하나요? 앱 키 등록") }
        }
        val error = localError ?: current?.error
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
    }
    if (showEnroll) AlertDialog(onDismissRequest = { showEnroll = false },
        title = { Text("차량에 앱 키 등록") },
        text = { Text("${activeProfile?.model} 가까이에서 차량 화면을 켜고 키 카드를 준비해 주세요.\n\n등록 요청 후 센터 콘솔의 카드 리더에 키 카드를 대고 차량 화면에서 승인합니다. 차량 정보를 조회하는 앱 전용 키가 추가됩니다.") },
        confirmButton = { TextButton(onClick = { showEnroll = false; onConnect(true) }) { Text("키 등록 시작") } },
        dismissButton = { TextButton(onClick = { showEnroll = false }) { Text("취소") } })
}

@Composable
private fun DestinationCard(selected: String?, state: DriveState, onRefresh: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val current = state.takeIf { it.vin == selected }
    val snapshot = current?.snapshot
    val age = current?.updatedAt?.let { ((now - it).coerceAtLeast(0) / 1000).toInt() }
    val intervalSeconds = (current?.pollIntervalMillis ?: 5_000) / 1000
    val stale = age != null && age >= intervalSeconds + 10
    val destination = snapshot?.destination
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("차량 내비게이션 목적지", fontSize = 13.sp, color = Muted)
            if (state.phase == DrivePhase.CONNECTED) TextButton(onClick = onRefresh) { Text("새로고침") }
        }
        val title = when {
            snapshot == null -> "연결을 기다리고 있어요"
            stale -> "목적지를 다시 확인하고 있어요"
            destination is NavigationDestination.Active -> destination.name ?: "좌표로 설정된 목적지"
            else -> "설정된 목적지가 없어요"
        }
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (snapshot != null && !stale) Mint else MaterialTheme.colorScheme.onSurface)
        if (!stale && destination is NavigationDestination.Active) {
            destination.point?.let { Text(String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            val details = listOfNotNull(snapshot.minutesToArrival?.let { "약 ${it.toInt()}분" }, snapshot.distanceKm?.let { String.format(Locale.KOREA, "%.1f km", it) })
            if (details.isNotEmpty()) Text(details.joinToString(" · "), fontSize = 17.sp)
        }
        Text(when {
            age == null -> "연결 후 차량에서 목적지를 자동으로 읽습니다."
            stale -> "마지막 수신 ${age}초 전 · 표시한 목적지를 확인해 주세요."
            else -> "${age}초 전 확인 · 차량 정보 ${intervalSeconds}초마다 확인"
        }, color = Muted, fontSize = 12.sp)
        if (snapshot != null) Text("목적지가 바뀌거나 경로를 이탈하면 경로를 다시 탐색합니다", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun Diagnostics(selected: String?, state: DriveState) {
    var diagnostics by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var diagnosticCopied by remember(state.events) { mutableStateOf(false) }
    val current = state.takeIf { it.vin == selected }
    TextButton(onClick = { diagnostics = !diagnostics }, modifier = Modifier.fillMaxWidth()) { Text(if (diagnostics) "연결 기록 접기" else "연결 기록 보기", color = Muted) }
    if (diagnostics) AppCard {
        Text("연결 기록", fontWeight = FontWeight.SemiBold)
        Text("최근 기록 160개 · 앱을 다시 열어도 유지됩니다", color = Muted, fontSize = 12.sp)
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(buildString {
                appendLine("Tesla Navi ${BuildConfig.VERSION_NAME} / Android ${Build.VERSION.RELEASE} / ${Build.MODEL}")
                appendLine("단계: ${state.phase}")
                appendLine("상태: ${state.status}")
                state.error?.let { appendLine("오류: $it") }
                append(state.events.joinToString("\n"))
            }))
            diagnosticCopied = true
        }) { Text(if (diagnosticCopied) "연결 기록 복사됨" else "연결 기록 복사") }
        current?.fingerprint?.let { Text("앱 키 식별자\n$it", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted) }
        Text(if (state.events.isEmpty()) "연결 기록이 없습니다." else state.events.joinToString("\n"), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ColumnScope.VersionFooter() {
    Text("개인용 · Tesla Navi ${BuildConfig.VERSION_NAME}", Modifier.align(Alignment.CenterHorizontally), color = Muted, fontSize = 11.sp)
}

@Composable
private fun CameraCard(state: CameraState, hasKey: Boolean, onMute: (Boolean) -> Unit, options: GuidanceOptions, onStop: () -> Unit) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("단속카메라 안내", fontWeight = FontWeight.SemiBold)
            if (hasKey) TextButton(onClick = { onMute(!state.muted) }) { Text(if (state.muted) "음성 켜기" else "음소거") }
        }
        if (state.phase == CameraPhase.INITIALIZING) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
        if (state.overSpeed) Text("과속 경고 · 제한속도보다 5 km/h 이상 빠릅니다", color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold)
        val next = state.overSpeedCamera ?: state.cameras.firstOrNull()
        if (next != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = Color(0xFF203732), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(next.speedLimitKph?.toString() ?: "—", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Mint)
                        Text("km/h", color = Mint, fontSize = 12.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(next.kind.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(formatCameraDistance(next.distanceMeters), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Mint)
                }
            }
            if (next.variableLimit) Text("가변 제한속도 · 현장 표지판을 확인해 주세요", color = Muted, fontSize = 12.sp)
            state.cameras.drop(1).forEach {
                Text("${it.kind.label} · ${formatCameraDistance(it.distanceMeters)}", color = Muted, fontSize = 13.sp)
            }
        } else Text(when (state.phase) {
            CameraPhase.GUIDING -> "안내 중인 단속카메라가 없습니다"
            CameraPhase.WAITING_GPS -> "현재 위치를 확인하고 있어요"
            else -> state.message
        }, fontSize = 17.sp, color = Muted)
        if (state.phoneFallback) {
            Text("차량 연결 끊김 · 마지막 목적지와 휴대폰 GPS로 안내 중", color = Mint, fontSize = 12.sp)
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("안내 종료") }
        }
        if (state.phase == CameraPhase.GUIDING && state.positionSource == dev.teslanavi.navigation.PositionSource.VEHICLE) {
            Text("휴대폰 GPS 수신 대기 · 차량 위치로 보조 안내 중", color = Mint, fontSize = 12.sp)
        }
        state.mode?.let {
            Text(if (it == NavigationMode.ROUTE) "카카오 경로 기준 · 테슬라 경로와 다를 수 있습니다" else "현재 주행 도로 기준 · 안전운행 모드", color = Muted, fontSize = 11.sp)
        }
        if (state.phase == CameraPhase.GUIDING) Text(when (state.roadClass) {
            RoadClass.HIGHWAY -> "고속도로 · 카메라 ${options.highwayMeters} m 전 안내"
            RoadClass.GENERAL -> "일반도로 · 카메라 ${options.generalMeters} m 전 안내"
            RoadClass.UNKNOWN -> "도로 종류 확인 중 · 카메라 ${options.highwayMeters} m 전 예비 안내"
        }, color = Muted, fontSize = 11.sp)
        if (state.mode != null) Text(state.message, color = Muted, fontSize = 12.sp)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        state.voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }

    }
}

private fun formatCameraDistance(meters: Int): String = if (meters >= 1000) String.format(Locale.KOREA, "%.1f km 앞", meters / 1000.0) else "$meters m 앞"

private enum class StatsSegment(val label: String) { CHARGE("충전"), DRIVE("주행") }

@Composable
private fun StatsScreen(state: DriveState, onOpenSettings: (() -> Unit)?, profiles: List<VehicleProfile>, profile: VehicleProfile?,
    entries: List<ChargeEntry>, trips: List<DriveTrip>, wonPerKwh: Int, odometerKm: Double?, onVehicle: (String) -> Unit,
    onRate: (Int) -> Unit, onSetRate: (String, Int?) -> Unit, onAdd: (Double, Double) -> Unit, onDelete: (String) -> Unit,
    onDeleteTrip: (String) -> Unit) {
    var segment by rememberSaveable { mutableStateOf(StatsSegment.CHARGE) }
    ScreenColumn {
        MainHeader(state, onOpenSettings, title = "통계")
        if (profile == null) AppCard {
            Text("차량을 먼저 등록해 주세요", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("충전·주행 기록은 차량별로 저장합니다. 차량을 등록하면 여기서 바로 볼 수 있습니다.", color = Muted)
        } else {
            if (profiles.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { item ->
                    val chosen = item.vin == profile.vin
                    FilterChip(selected = chosen, onClick = { onVehicle(item.vin) }, label = { Text(item.label, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = if (chosen) { { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                        colors = statsChipColors(), border = statsChipBorder(chosen))
                }
            }
            MonthStrip(entries, trips, wonPerKwh)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsSegment.entries.forEach { item ->
                    val chosen = item == segment
                    FilterChip(selected = chosen, onClick = { segment = item }, modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        label = { Text(item.label, Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold, fontSize = 15.sp) },
                        leadingIcon = { Icon(if (item == StatsSegment.CHARGE) EvStationOutlined else RouteOutlined, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        colors = statsChipColors(), border = statsChipBorder(chosen))
                }
            }
            if (segment == StatsSegment.CHARGE) ChargeStats(entries, wonPerKwh, odometerKm, onRate, onSetRate, onAdd, onDelete)
            else DriveStats(trips, onDeleteTrip)
        }
        VersionFooter()
    }
}

@Composable
private fun statsChipColors() = FilterChipDefaults.filterChipColors(labelColor = Muted, iconColor = Muted, selectedContainerColor = Color(0xFF203732),
    selectedLabelColor = Mint, selectedLeadingIconColor = Mint)

@Composable
private fun statsChipBorder(chosen: Boolean) = FilterChipDefaults.filterChipBorder(enabled = true, selected = chosen,
    borderColor = Color(0xFF2F3A47), selectedBorderColor = Mint)

/** This month's driving and charging together: the one view the two segments cannot give on their own. */
@Composable
private fun MonthStrip(entries: List<ChargeEntry>, trips: List<DriveTrip>, wonPerKwh: Int) {
    val month = java.time.YearMonth.now()
    fun inMonth(timeMs: Long) = java.time.YearMonth.from(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())) == month
    val km = trips.filter { inMonth(it.startMs) }.sumOf { it.distanceKm }
    val charges = entries.filter { inMonth(it.timeMs) }
    val kwh = charges.sumOf { it.energyKwh }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFF242D38)) {
        val parts = listOfNotNull(String.format(Locale.KOREA, "주행 %,.0f km", km).takeIf { km > 0 },
            String.format(Locale.KOREA, "충전 %.1f kWh · 약 %,d원", kwh, ChargeLogbook.cost(charges, wonPerKwh).roundToLong()).takeIf { kwh > 0 })
        Text(if (parts.isEmpty()) "이번 달 기록이 아직 없습니다" else (listOf("이번 달") + parts).joinToString(" · "),
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun ChargeStats(entries: List<ChargeEntry>, wonPerKwh: Int, odometerKm: Double?, onRate: (Int) -> Unit,
    onSetRate: (String, Int?) -> Unit, onAdd: (Double, Double) -> Unit, onDelete: (String) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var editingRate by remember { mutableStateOf<ChargeEntry?>(null) }
    var showRate by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChargeEntry?>(null) }
    val summary = remember(entries, wonPerKwh) { ChargeLogbook.summarize(entries, wonPerKwh) }
    val months = remember(summary) { groupByMonth(summary.intervals.asReversed(), ZoneId.systemDefault()) { it.entry.timeMs } }
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("최근 충전", Modifier.weight(1f), color = Muted, fontSize = 13.sp)
            TextButton(onClick = { showAdd = true }) { Text("+ 충전 기록") }
        }
        val latest = summary.latest
        if (latest?.kmPerKwh != null && latest.wonPerKm != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricBox(formatDecimal(latest.kmPerKwh), "km/kWh", Modifier.weight(1f))
                MetricBox(formatDecimal(latest.wonPerKm), "원/km", Modifier.weight(1f))
            }
            Text("주행 ${formatKm(latest.distanceKm ?: 0.0)} · 충전 ${formatDecimal(latest.entry.energyKwh)} kWh", fontSize = 15.sp)
        } else Text(if (entries.isEmpty()) "첫 충전을 기록해 주세요" else "다음 충전을 기록하면 전비를 계산합니다", fontSize = 17.sp, color = Muted)
        if (summary.measuredCount >= 2 && summary.kmPerKwh != null && summary.wonPerKm != null) Text(
            "전체 평균 ${formatDecimal(summary.kmPerKwh)} km/kWh · ${formatWonPerKm(summary.wonPerKm)} · 누적 ${formatKm(summary.distanceKm)}", fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("기본 충전 단가 ${String.format(Locale.KOREA, "%,d", wonPerKwh)}원/kWh", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
            TextButton(onClick = { showRate = true }) { Text("단가 변경") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("충전 기록", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { showInfo = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = "충전 기록 안내", tint = Muted, modifier = Modifier.size(20.dp))
            }
        }
        if (months.isEmpty()) AppCard { Text("충전 기록이 없습니다.", color = Muted) }
        months.forEach { group ->
            val charges = group.items.map { it.entry }
            MonthHeader(group.month, String.format(Locale.KOREA, "%d회 · %.1f kWh · 약 %,d원", charges.size, charges.sumOf { it.energyKwh },
                ChargeLogbook.cost(charges, wonPerKwh).roundToLong()))
            group.items.forEach { interval ->
                ChargeTile(interval, onEditRate = { editingRate = interval.entry }) { pendingDelete = interval.entry }
            }
        }
    }
    if (showRate) RateDialog(wonPerKwh, onDismiss = { showRate = false }, onSave = { onRate(it); showRate = false })
    editingRate?.let { entry ->
        EntryRateDialog(entry, wonPerKwh, onDismiss = { editingRate = null }, onSave = { onSetRate(entry.id, it); editingRate = null })
    }
    if (showAdd) ChargeDialog(odometerKm, onDismiss = { showAdd = false }, onSave = { odometer, energy -> onAdd(odometer, energy); showAdd = false })
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false },
        title = { Text("충전 기록 안내") },
        text = { Text("차량이 연결된 동안 충전 케이블이 분리된 상태에서 새 충전이 확인되면 충전량과 주행거리를 자동으로 기록합니다.\n\n" +
            "전비는 이전 충전 이후 주행거리를 이번 충전량으로 나눈 값입니다.") },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("닫기") } })
    pendingDelete?.let { entry ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text("충전 기록 삭제") },
            text = { Text("${formatChargeTime(entry.timeMs)} · ${formatKm(entry.odometerKm)} · ${formatDecimal(entry.energyKwh)} kWh 기록을 삭제할까요? 다음 충전의 전비도 다시 계산됩니다.") },
            confirmButton = { TextButton(onClick = { onDelete(entry.id); pendingDelete = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } })
    }
}

@Composable
private fun DriveStats(trips: List<DriveTrip>, onDelete: (String) -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DriveTrip?>(null) }
    val newestFirst = remember(trips) { trips.sortedByDescending { it.startMs } }
    val months = remember(newestFirst) { groupByMonth(newestFirst, ZoneId.systemDefault()) { it.startMs } }
    AppCard {
        Text("최근 주행", color = Muted, fontSize = 13.sp)
        val latest = newestFirst.firstOrNull()
        if (latest != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricBox(formatDecimal(latest.distanceKm), "km", Modifier.weight(1f))
                MetricBox((latest.durationMs / 60_000).toString(), "분", Modifier.weight(1f))
            }
            // Labeled cells instead of one "·" sentence, which broke mid-word on narrow screens.
            Row(Modifier.fillMaxWidth()) {
                TileMetric("평균 속도", latest.averageKph?.let { "${it.roundToInt()} km/h" } ?: "—", Modifier.weight(1f))
                TileMetric("최고 속도", "${latest.maxSpeedKph.roundToInt()} km/h", Modifier.weight(1f))
                TileMetric("배터리", latest.batteryUsed?.let { formatBatteryUsed(it) } ?: "—", Modifier.weight(1f))
            }
        } else Text("차량이 연결된 동안 주행하면 자동으로 기록합니다", fontSize = 17.sp, color = Muted)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("주행 기록", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { showInfo = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = "주행 기록 안내", tint = Muted, modifier = Modifier.size(20.dp))
            }
        }
        if (months.isEmpty()) AppCard { Text("주행 기록이 없습니다.", color = Muted) }
        months.forEach { group ->
            val km = group.items.sumOf { it.distanceKm }
            val minutes = group.items.sumOf { it.durationMs } / 60_000
            MonthHeader(group.month, String.format(Locale.KOREA, "%d회 · %,.0f km · %s", group.items.size, km, formatMinutes(minutes)))
            group.items.forEachIndexed { index, trip ->
                DriveTile(trip) { pendingDelete = trip }
                // Distance the odometer moved between two logged trips was driven without the app.
                val older = group.items.getOrNull(index + 1)
                val gap = if (older?.endKm != null && trip.startKm != null) trip.startKm - older.endKm else null
                if (gap != null && gap >= 1.0) Text(String.format(Locale.KOREA, "기록되지 않은 주행 약 %,.0f km", gap),
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false },
        title = { Text("주행 기록 안내") },
        text = { Text("차량이 연결된 동안 P단을 벗어나 3 km/h 이상으로 움직이면 주행을 시작하고, P단에 두면 끝냅니다. 카메라 안내와 같은 기준입니다.\n\n" +
            "0.3 km 미만 이동은 남기지 않습니다. 휴대폰 없이 탄 구간은 다음 기록과의 주행거리 차이로 표시합니다.") },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("닫기") } })
    pendingDelete?.let { trip ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text("주행 기록 삭제") },
            text = { Text("${formatChargeTime(trip.startMs)} · ${formatDecimal(trip.distanceKm)} km 주행 기록을 삭제할까요?") },
            confirmButton = { TextButton(onClick = { onDelete(trip.id); pendingDelete = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } })
    }
}

/** Leading distance badge, time range as the headline, then labeled values, matching the charge tile. */
@Composable
private fun DriveTile(trip: DriveTrip, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Panel) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(56.dp), shape = RoundedCornerShape(14.dp), color = Color(0xFF203732)) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        val (distance, unit) = tripDistance(trip.distanceKm)
                        Text(distance, fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = Mint, maxLines = 1)
                        Text(unit, fontSize = 10.sp, lineHeight = 12.sp, color = Mint)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatTripDay(trip.startMs), color = Muted, fontSize = 13.sp)
                    Text("${formatClock(trip.startMs)} → ${formatClock(trip.endMs)}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Mint)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "주행 기록 메뉴", tint = Muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("기록 삭제") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(end = 14.dp), color = Color(0xFF29323D))
            Row(Modifier.fillMaxWidth().padding(end = 14.dp)) {
                TileMetric("운전 시간", formatMinutes(trip.durationMs / 60_000), Modifier.weight(1f))
                TileMetric("평균 속도", trip.averageKph?.let { "${it.roundToInt()} km/h" } ?: "—", Modifier.weight(1f))
                TileMetric("배터리", trip.batteryUsed?.let { formatBatteryUsed(it) } ?: "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricBox(value: String, unit: String, modifier: Modifier) {
    Surface(modifier, color = Color(0xFF203732), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Mint)
            Text(unit, color = Mint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MonthHeader(month: java.time.YearMonth, summary: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(String.format(Locale.KOREA, "%d년 %d월", month.year, month.monthValue), color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(summary, color = Muted, fontSize = 12.sp)
    }
}

/** Leading efficiency badge, cost as the headline, then labeled values: scanned in that order. */
@Composable
private fun ChargeTile(interval: ChargeInterval, onEditRate: () -> Unit, onDelete: () -> Unit) {
    val entry = interval.entry
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Panel) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EfficiencyBadge(interval)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatChargeDay(entry.timeMs), color = Muted, fontSize = 13.sp)
                    Text(interval.wonPerKm?.let { formatWonPerKm(it) } ?: if (interval.distanceKm == null) "기준 기록" else "주행 없음",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (interval.wonPerKm != null) Mint else Muted)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "충전 기록 메뉴", tint = Muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("충전단가 수정") }, onClick = { menu = false; onEditRate() })
                        DropdownMenuItem(text = { Text("기록 삭제") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(end = 14.dp), color = Color(0xFF29323D))
            Row(Modifier.fillMaxWidth().padding(end = 14.dp)) {
                TileMetric("주행", interval.distanceKm?.takeIf { it > 0 }?.let { "+${formatKm(it)}" } ?: "—", Modifier.weight(1f))
                TileMetric("충전량", "${formatDecimal(entry.energyKwh)} kWh", Modifier.weight(1f))
                TileMetric("누적", formatKm(entry.odometerKm), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EfficiencyBadge(interval: ChargeInterval) {
    val value = interval.kmPerKwh
    Surface(Modifier.size(56.dp), shape = RoundedCornerShape(14.dp), color = if (value != null) Color(0xFF203732) else Color(0xFF242D38)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (value != null) {
                Text(formatDecimal(value), fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = Mint)
                Text("km/kWh", fontSize = 9.sp, lineHeight = 11.sp, color = Mint)
            } else Text(if (interval.distanceKm == null) "기준" else "—", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Muted)
        }
    }
}

@Composable
private fun TileMetric(label: String, value: String, modifier: Modifier) {
    // Label and value centre on each other; the pair stays at the cell start so it lines up with the badge above.
    Box(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Muted, fontSize = 11.sp)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChargeDialog(odometerKm: Double?, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var odometer by remember { mutableStateOf(odometerKm?.toLong()?.toString() ?: "") }
    var energy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun decimal(input: String) = input.filter { it.isDigit() || it == '.' }.take(9)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("충전 기록 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(value = odometer, onValueChange = { odometer = decimal(it); error = null },
                    label = { Text("누적 주행거리 (km)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text(if (odometerKm != null) "연결된 차량의 주행거리를 불러왔습니다" else "충전을 마친 시점의 주행거리계 값") })
                OutlinedTextField(value = energy, onValueChange = { energy = decimal(it); error = null },
                    label = { Text("충전량 (kWh)") }, singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text(error ?: "충전기 또는 Tesla 앱에 표시된 충전량") })
            }
        },
        confirmButton = { TextButton(onClick = {
            try {
                onSave(odometer.toDoubleOrNull() ?: throw IllegalArgumentException("누적 주행거리를 확인해 주세요."),
                    energy.toDoubleOrNull() ?: throw IllegalArgumentException("충전량을 확인해 주세요."))
            } catch (failure: IllegalArgumentException) { error = failure.message ?: "충전 기록 저장 실패" }
        }, enabled = odometer.isNotBlank() && energy.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun RateDialog(wonPerKwh: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var rate by remember { mutableStateOf(wonPerKwh.toString()) }
    val value = rate.toIntOrNull()?.takeIf { it in 1..2000 }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("기본 충전 단가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = rate, onValueChange = { rate = it.filter(Char::isDigit).take(4) },
                    label = { Text("기본 충전 단가 (원/kWh)") }, singleLine = true, isError = rate.isNotEmpty() && value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("기본 ${DEFAULT_WON_PER_KWH}원 · 환경부 공공 완속(2026년 8월 개편) 기준. 단가를 따로 정한 충전에는 적용되지 않습니다.") })
                TextButton(onClick = { rate = DEFAULT_WON_PER_KWH.toString() }) { Text("기본값으로") }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

/** One charge's own rate; saving the default value still pins it, the reset button makes it follow the default again. */
@Composable
private fun EntryRateDialog(entry: ChargeEntry, defaultWonPerKwh: Int, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var rate by remember { mutableStateOf(entry.rate(defaultWonPerKwh).toString()) }
    val value = rate.toIntOrNull()?.takeIf { it in 1..2000 }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("충전단가 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatChargeTime(entry.timeMs)} · ${formatDecimal(entry.energyKwh)} kWh 충전", color = Muted, fontSize = 13.sp)
                OutlinedTextField(value = rate, onValueChange = { rate = it.filter(Char::isDigit).take(4) },
                    label = { Text("이 충전의 단가 (원/kWh)") }, singleLine = true, isError = rate.isNotEmpty() && value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("이 충전의 km당 충전비와 충전비 합계에 반영됩니다") })
                if (entry.wonPerKwh != null) TextButton(onClick = { onSave(null) }) {
                    Text("기본 단가(${String.format(Locale.KOREA, "%,d", defaultWonPerKwh)}원)로 되돌리기")
                }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

private fun formatDecimal(value: Double) = String.format(Locale.KOREA, "%.1f", value)
private fun formatWonPerKm(value: Double) = String.format(Locale.KOREA, "%.1f원/km", value)
private fun formatKm(value: Double) = String.format(Locale.KOREA, "%,.0f km", value)
private fun formatTripDay(timeMs: Long): String =
    DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA).format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))
private fun formatClock(timeMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA).format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))
/** Trip list badge only: whole kilometres, cut rather than rounded (20.6 → 20 km), or 100 m steps under 1 km. */
private fun tripDistance(km: Double): Pair<String, String> =
    if (km < 1.0) ((km * 10).toInt() * 100).toString() to "m" else String.format(Locale.KOREA, "%,d", km.toLong()) to "km"
private fun formatMinutes(minutes: Long): String = when {
    minutes < 60 -> "${minutes}분"
    minutes % 60 == 0L -> "${minutes / 60}시간"
    else -> "${minutes / 60}시간 ${minutes % 60}분"
}
private fun formatBatteryUsed(points: Int): String = if (points > 0) "-${points}%" else if (points < 0) "+${-points}%" else "0%"
private fun formatChargeDay(timeMs: Long): String =
    DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREA).format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))
private fun formatChargeTime(timeMs: Long): String =
    DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREA).format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))

@Composable
private fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Panel) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun VehicleDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var vin by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("Model 3") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("차량 등록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Model 3", "Model Y").forEach { item -> FilterChip(selected = model == item, onClick = { model = item }, label = { Text(item) }) }
                }
                OutlinedTextField(value = vin, onValueChange = { vin = VehicleIdentity.normalizeVin(it); error = null },
                    label = { Text("차대번호(VIN) 17자리") }, singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    supportingText = { Text(error ?: "Tesla 앱 또는 차량의 제어 > 소프트웨어에서 확인할 수 있습니다.") })
            }
        },
        confirmButton = { TextButton(onClick = {
            try { onSave(model, vin) } catch (failure: Exception) { error = failure.message ?: "차량 저장 실패" }
        }, enabled = vin.isNotBlank()) { Text("등록") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun GuidanceSettingsDialog(app: TeslaNaviApp, guidanceActive: Boolean, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("안내 설정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GuidanceSettingsContent(app, guidanceActive) { content ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}

/** The settings tab's guidance page, one level below the settings list. Back returns to the list. */
@Composable
private fun GuidanceSettingsPage(app: TeslaNaviApp, guidanceActive: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text("안내 설정", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        GuidanceSettingsContent(app, guidanceActive) { content -> AppCard(content) }
    }
}

/** Guidance controls in four sections; the dialog stacks them and the page puts each in a card. Changes save immediately. */
@Composable
private fun GuidanceSettingsContent(app: TeslaNaviApp, guidanceActive: Boolean,
    section: @Composable (@Composable ColumnScope.() -> Unit) -> Unit) {
    val settings = app.guidance
    val options by settings.options.collectAsStateWithLifecycle()
    val geminiKey by app.geminiSettings.state.collectAsStateWithLifecycle()
    val geminiStatus by app.gemini.status.collectAsStateWithLifecycle()
    var volume by remember(options.volumePercent) { mutableFloatStateOf(options.volumePercent.toFloat()) }
    var highway by remember(options.highwayMeters) { mutableFloatStateOf(options.highwayMeters.toFloat()) }
    var general by remember(options.generalMeters) { mutableFloatStateOf(options.generalMeters.toFloat()) }
    section {
        Text("안내 음량 ${volume.roundToInt()}%", fontWeight = FontWeight.SemiBold)
        Slider(value = volume, onValueChange = { volume = it }, onValueChangeFinished = { settings.volume(volume.roundToInt()) },
            valueRange = 0f..100f, steps = 19, modifier = Modifier.semantics { contentDescription = "안내 음량" })
        Text("휴대폰 미디어 음량 기준 · 음성과 경고음에 함께 적용", fontSize = 12.sp, color = Muted)
    }
    section {
        GuidanceVoiceChoice(options.voice, onSelect = app::selectVoice,
            speaker = options.geminiSpeaker, onSpeaker = app::selectGeminiSpeaker,
            speedPercent = options.geminiSpeedPercent, onSpeed = settings::geminiSpeed,
            geminiKey = geminiKey, geminiStatus = geminiStatus, onSaveGemini = app::saveGeminiKey, onDeleteGemini = app::deleteGeminiKey)
    }
    section { GuidanceSoundCheck(volume.roundToInt(), guidanceActive, app::guidancePreviewSpeaker,
        selectionKey = "${options.voice}/${options.geminiSpeaker}/${options.geminiSpeedPercent}/${geminiKey.revision}",
        voiceStatus = geminiStatus.takeIf { options.voice == GuidanceVoice.GEMINI }) }
    section { DepartureDetectionControl(app.walkingSteps) }
    section {
        Text("고속도로 ${highway.roundToInt()} m 전", fontWeight = FontWeight.SemiBold)
        Slider(value = highway, onValueChange = { highway = it }, onValueChangeFinished = { settings.highway(highway.roundToInt()) },
            valueRange = 100f..2000f, steps = 18, modifier = Modifier.semantics { contentDescription = "고속도로 안내 거리" })
        Text("일반도로 ${general.roundToInt()} m 전", fontWeight = FontWeight.SemiBold)
        Slider(value = general, onValueChange = { general = it }, onValueChangeFinished = { settings.general(general.roundToInt()) },
            valueRange = 100f..1000f, steps = 8, modifier = Modifier.semantics { contentDescription = "일반도로 안내 거리" })
        Text("카메라 사전 안내와 +5 km/h 과속 경고 구간에 적용됩니다. 도로 종류를 모르면 고속도로 설정을 사용합니다.", fontSize = 12.sp, color = Muted)
        Text("설정은 자동 저장됩니다. 카메라 정보 수신 시점에 따라 안내가 늦어질 수 있습니다.", fontSize = 12.sp, color = Muted)
        TextButton(onClick = { settings.reset() }) { Text("기본값 복원") }
    }
}
