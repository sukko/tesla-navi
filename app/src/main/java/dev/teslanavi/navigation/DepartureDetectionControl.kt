package dev.teslanavi.navigation

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun DepartureDetectionControl(monitor: WalkingSteps) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var permitted by remember { mutableStateOf(monitor.permitted) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = monitor.permitted; denied = !it
    }
    DisposableEffect(lifecycle, monitor) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permitted = monitor.permitted
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Text("연결 끊김 후 하차 감지", fontWeight = FontWeight.SemiBold)
    Text("차량 연결이 끊긴 뒤 걸음과 느린 GPS 이동이 2분 이상 함께 확인되면 하차로 추정해 안내를 종료합니다. " +
        "GPS 속도나 Bluetooth 해제만으로는 하차로 판단하지 않습니다.", fontSize = 12.sp, color = Hint)
    when {
        !monitor.supported -> Text("이 휴대폰은 걸음 감지를 지원하지 않습니다. 기존 10분 정지 종료 규칙을 적용합니다.", fontSize = 12.sp, color = Hint)
        permitted -> Text("걸음 감지 사용 가능 · 연결 끊김 후 안내 중에만 사용", fontSize = 12.sp, color = Hint)
        else -> {
            Text("선택 사항입니다. 신체 활동 권한을 허용하지 않아도 안내 유지와 10분 정지 종료는 동작합니다. 걸음 기록은 저장하지 않습니다.", fontSize = 12.sp, color = Hint)
            OutlinedButton(onClick = { launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("신체 활동 권한 허용") }
            if (denied) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("앱 권한 설정 열기") }
        }
    }
}
