package dev.teslanavi.navigation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GeminiKeyState(val hasKey: Boolean = false, val revision: Int = 0, val error: String? = null)

/** Personal API key: encrypted at rest, excluded from backup, never part of the APK or preferences. */
class GeminiSettings(context: Context, private val wrappingKey: () -> SecretKey = ::geminiWrappingKey) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "gemini-api-key.bin"))
    @Volatile private var key = ""
    private val mutable = MutableStateFlow(GeminiKeyState())
    val state = mutable.asStateFlow()

    init {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            try {
                val bytes = file.openRead().use { it.readBytes() }
                require(bytes.size in 29..4096)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                cipher.updateAAD(AAD)
                key = cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
                mutable.value = GeminiKeyState(hasKey = key.isNotBlank())
            } catch (_: Exception) {
                mutable.value = GeminiKeyState(error = "저장된 Gemini 키를 읽지 못했습니다. 다시 등록해 주세요.")
            }
        }
    }

    fun apiKey(): String = key

    @Synchronized fun save(raw: String) {
        val value = raw.trim()
        require(value.length in 20..2048 && value.all { it.code in 33..126 }) {
            "API 키를 공백 없이 입력해 주세요."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(AAD)
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
        key = value
        mutable.value = GeminiKeyState(true, mutable.value.revision + 1)
    }

    @Synchronized fun delete() {
        file.delete()
        key = ""
        mutable.value = GeminiKeyState(false, mutable.value.revision + 1)
    }

    companion object { private val AAD = "tesla-navi-gemini-key-v1".toByteArray(Charsets.UTF_8) }
}

private fun geminiWrappingKey(): SecretKey {
    val alias = "tesla-navi-gemini-wrap-v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generateKey()
    }
}
