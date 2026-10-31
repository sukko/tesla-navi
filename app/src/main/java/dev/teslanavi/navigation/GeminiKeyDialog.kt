package dev.teslanavi.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun GeminiKeyDialog(hasKey: Boolean, onSave: (String) -> String?, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val links = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.width(320.dp),
        title = { Text("Gemini API 키", maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Google AI Studio에서 키를 만든 뒤 붙여 넣으세요. 키는 이 휴대폰에 암호화해 저장합니다.", maxLines = 8)
                OutlinedTextField(value = input, onValueChange = { input = it; error = null },
                    label = { Text(if (hasKey) "새 API 키" else "API 키 입력", maxLines = 1) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null)
                error?.let { Text(it, maxLines = 8) }
                Text("무료 사용량은 프로젝트별로 제한됩니다. 무료로만 쓰려면 결제 계정을 연결하지 말고 Free tier 상태를 확인하세요.", maxLines = 8)
                Text("안내 문장을 Google로 전송합니다. 무료 구간의 입력·출력은 서비스 개선에 사용될 수 있습니다. 연결 실패·한도 초과 시 기본 음성으로 안내합니다.", maxLines = 8)
                TextButton(onClick = { runCatching { links.openUri("https://aistudio.google.com/apikey") } }) { Text("Google AI Studio에서 키 만들기", maxLines = 2) }
                if (hasKey) TextButton(onClick = { onDelete(); onDismiss() }) { Text("저장된 키 삭제", maxLines = 1) }
            }
        },
        confirmButton = { TextButton(enabled = input.isNotBlank(), onClick = {
            error = onSave(input)
            if (error == null) { input = ""; onDismiss() }
        }) { Text("저장·사용", maxLines = 1) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", maxLines = 1) } })
}
