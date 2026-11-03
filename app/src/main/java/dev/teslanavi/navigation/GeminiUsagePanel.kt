package dev.teslanavi.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Refresh only while this settings UI is visible; quota recovery never needs a background timer. */
@Composable
internal fun GeminiUsagePanel(usage: GeminiTtsUsage) {
    val state by usage.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(usage, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { usage.refresh(); delay(5000) }
        }
    }
    Text("Gemini 사용량 · 앱에서 집계", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text("자동 전환: ${GeminiTtsModel.entries.joinToString(" → ") { it.label }}", fontSize = 13.sp, color = Hint)
    Text("새 음성 생성: ${state.model.label}", fontSize = 13.sp, color = Hint)
    listOf(GeminiTtsModel.PRIMARY to state.primary, GeminiTtsModel.FALLBACK to state.fallback,
        GeminiTtsModel.FLASH to state.flash, GeminiTtsModel.FLASH25 to state.flash25).forEach { (model, used) ->
        Text("${model.label}\n최근 1분 ${used.rpm}/${GeminiTtsUsage.MINUTE_LIMIT}회 · 하루 ${used.rpd}/${GeminiTtsUsage.DAILY_LIMIT}회", fontSize = 13.sp, color = Hint)
    }
    state.reason?.let { Text(it, fontSize = 13.sp, color = Hint) }
    state.error?.let { Text(it, fontSize = 13.sp, color = Hint) }
    val reset = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.resetsAt))
    Text("기본 모델 복귀·일일 초기화: $reset (휴대폰 시간)", fontSize = 13.sp, color = Hint)
    Text("앞의 세 모델은 분당 ${GeminiTtsUsage.EARLY_MINUTE}회·하루 ${GeminiTtsUsage.EARLY_DAILY}회부터 다음 모델로 전환합니다. 캐시 재생은 제외하며, 다른 앱·AI Studio의 사용량은 포함하지 않습니다.",
        fontSize = 13.sp, color = Hint)
}
