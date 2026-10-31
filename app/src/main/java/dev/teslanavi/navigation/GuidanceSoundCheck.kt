package dev.teslanavi.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun GuidanceSoundCheck(volumePercent: Int, guidanceActive: Boolean, createSpeaker: () -> CameraSpeaker, selectionKey: String = "",
    voiceStatus: String? = null) {
    val scope = rememberCoroutineScope()
    val preview = remember(scope) { GuidanceAudioPreview(scope, createSpeaker) }
    val state by preview.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(volumePercent) { preview.setVolume(volumePercent) }
    LaunchedEffect(guidanceActive) { if (guidanceActive) preview.stop() }
    LaunchedEffect(selectionKey) { preview.stop() }
    DisposableEffect(preview, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) preview.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); preview.stop() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { preview.play(PreviewSound.CAMERA_VOICE, volumePercent) },
            enabled = !guidanceActive, modifier = Modifier.fillMaxWidth()) { Text("카메라 음성 테스트") }
        OutlinedButton(onClick = { preview.play(PreviewSound.OVERSPEED_CHIME, volumePercent) },
            enabled = !guidanceActive, modifier = Modifier.fillMaxWidth()) { Text("과속 경고음 테스트") }
        if (state.playing != null) {
            Text(if (state.playing == PreviewSound.CAMERA_VOICE) "카메라 음성 테스트 중" else "과속 경고음 테스트 중 · 약 6초")
            TextButton(onClick = preview::stop) { Text("테스트 중지") }
        }
        state.message?.let { Text(it) }
        voiceStatus?.let { Text(it, fontSize = 12.sp, color = Hint) }
        Text(if (guidanceActive) "실제 주행 안내 중에는 소리 테스트를 사용할 수 없습니다."
            else "차량 연결 없이 테스트할 수 있습니다. 소리가 안 들리면 휴대폰 미디어 음량과 출력 기기를 확인해 주세요.",
            fontSize = 12.sp, color = Hint)
    }
}
