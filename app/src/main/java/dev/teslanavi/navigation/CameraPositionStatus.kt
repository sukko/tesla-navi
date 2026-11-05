package dev.teslanavi.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Use the current guidance phase before the last source, which may remain set while both fixes are stale. */
@Composable
internal fun CameraPositionStatus(state: CameraState) {
    val title: String
    val detail: String
    val color: Color
    val background: Color
    val phase = if (state.phase == CameraPhase.GUIDING && (state.locationAtMillis == null ||
        (state.positionSource == PositionSource.VEHICLE && (!state.vehicleAvailable || state.phoneFallback))))
        CameraPhase.WAITING_GPS else state.phase
    when (phase) {
        CameraPhase.GUIDING -> if (state.positionSource == PositionSource.VEHICLE) {
            title = "차량 위치로 보조 안내 중"
            detail = "휴대폰 GPS 수신 대기 · 차량 위치로 카메라 거리를 계산합니다."
            color = Color(0xFF91CFFF); background = Color(0xFF203244)
        } else {
            title = "휴대폰 GPS 안내 중"
            detail = if (state.phoneFallback) "차량 연결 끊김 · 휴대폰 GPS로 마지막 목적지의 카메라를 안내합니다."
                else "휴대폰 위치로 카메라 거리를 계산합니다."
            color = Color(0xFF69E3CD); background = Color(0xFF203732)
        }
        CameraPhase.WAITING_GPS -> if (state.stoppedInDrive) {
            title = "정차 중 · 안내 유지"
            detail = "위치가 다시 갱신되면 카메라 안내를 이어갑니다."
            color = Color(0xFFA2AEBD); background = Color(0xFF242D38)
        } else {
            title = "위치 수신 대기"
            detail = when {
                state.phoneFallback || !state.vehicleAvailable -> "휴대폰 GPS를 기다리고 있습니다. 새 카메라 안내는 잠시 보류합니다."
                state.mode == NavigationMode.SAFETY -> "휴대폰 GPS 수신 대기 · 경로 정보가 없어 차량 위치 보조 안내를 사용할 수 없습니다."
                else -> "휴대폰 GPS와 차량 위치를 확인 중입니다. 새 카메라 안내는 잠시 보류합니다."
            }
            color = Color(0xFFFFD08A); background = Color(0xFF3B3020)
        }
        CameraPhase.INITIALIZING -> {
            title = "위치 안내 준비 중"
            detail = "안내에 사용할 경로와 위치 정보를 준비합니다."
            color = Color(0xFFA2AEBD); background = Color(0xFF242D38)
        }
        else -> return
    }
    Surface(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        color = background, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = color, fontSize = 12.sp)
        }
    }
}
