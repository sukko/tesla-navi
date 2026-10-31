package dev.teslanavi.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Hint = Color(0xFFA2AEBD)

/** Radio (24dp) plus its gap: option details and actions start here so they line up under the titles. */
private val OptionIndent = 36.dp

@Composable
fun GuidanceVoiceChoice(selected: GuidanceVoice, onSelect: (GuidanceVoice) -> Unit,
    speaker: GeminiSpeaker = GeminiSpeaker.Kore, onSpeaker: (GeminiSpeaker) -> Unit = {},
    speedPercent: Int = 125, onSpeed: (Int) -> Unit = {},
    geminiKey: GeminiKeyState = GeminiKeyState(), geminiStatus: String? = null,
    onSaveGemini: (String) -> String? = { "키를 저장할 수 없습니다." }, onDeleteGemini: () -> Unit = {}) {
    val active = if (selected == GuidanceVoice.GEMINI && !geminiKey.hasKey) GuidanceVoice.SYSTEM else selected
    var showSpeakers by remember { mutableStateOf(false) }
    var speed by remember(speedPercent) { mutableStateOf(speedPercent.toFloat()) }
    var showGemini by remember { mutableStateOf(false) }
    Column {
        Text("안내 음성", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        VoiceOption("휴대폰 기본 음성", "휴대폰에 설치된 TTS 엔진", active == GuidanceVoice.SYSTEM, true) {
            onSelect(GuidanceVoice.SYSTEM)
        }
        VoiceOption("Gemini AI 음성", if (geminiKey.hasKey) "키 등록됨 · 인터넷으로 합성" else "API 키 등록 필요",
            active == GuidanceVoice.GEMINI, geminiKey.hasKey) { onSelect(GuidanceVoice.GEMINI) }
        Column(Modifier.padding(start = OptionIndent), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { showGemini = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (geminiKey.hasKey) "Gemini 키 변경" else "Gemini API 키 등록")
            }
            Text("반복 안내는 저장한 음성으로 재생 · 연결 실패 시 기본 음성 사용", fontSize = 13.sp, color = Hint)
            (geminiKey.error ?: geminiStatus)?.let { Text(it, fontSize = 13.sp, color = Hint) }
        }
        Column(Modifier.padding(start = OptionIndent), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { showSpeakers = true }, modifier = Modifier.fillMaxWidth(), enabled = geminiKey.hasKey) {
                Text("목소리 변경 · ${speaker.label}", maxLines = 2)
            }
            Text("Gemini 말하기 속도 ${String.format(java.util.Locale.US, "%.2f", speed / 100)}배", fontSize = 14.sp)
            Slider(value = speed, onValueChange = { speed = it }, onValueChangeFinished = { onSpeed(speed.roundToInt()) },
                valueRange = 80f..150f, steps = 13, enabled = geminiKey.hasKey,
                modifier = Modifier.semantics { contentDescription = "Gemini 말하기 속도" })
            Text("목소리 높이를 유지하며 재생 속도 조절 · 아래 카메라 음성 테스트로 확인", fontSize = 13.sp, color = Hint)
            Text("처음 만드는 음성은 시간이 걸릴 수 있습니다. 주행 중 응답이 늦으면 기본 음성으로 먼저 안내합니다.", fontSize = 13.sp, color = Hint)
        }
    }
    if (showGemini) GeminiKeyDialog(geminiKey.hasKey, onSaveGemini, onDeleteGemini) { showGemini = false }
    if (showSpeakers) AlertDialog(modifier = Modifier.width(320.dp), onDismissRequest = { showSpeakers = false },
        title = { Text("Gemini 목소리", maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth().height(340.dp).verticalScroll(rememberScrollState())) {
                GeminiSpeaker.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .selectable(speaker == option, role = Role.RadioButton, onClick = {
                            onSpeaker(option); showSpeakers = false
                        }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = speaker == option, onClick = null)
                        Text(option.label, maxLines = 2)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showSpeakers = false }) { Text("닫기", maxLines = 1) } })
}

/** One radio option. The radio sits on the title line; the whole row is the touch target. */
@Composable
private fun VoiceOption(title: String, detail: String, selected: Boolean, enabled: Boolean,
    onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .selectable(selected, enabled, Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(OptionIndent - 24.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, color = if (enabled) Color.Unspecified else Hint)
            }
            Text(detail, fontSize = 13.sp, color = Hint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
