package dev.teslanavi.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiSettingsTest {
    @Test fun keyIsEncryptedSurvivesReloadAndDeleteDoesNotTouchVehicleMaterial() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipherKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val settings = GeminiSettings(context) { cipherKey }
        val secret = "AQ.local-test-key-for-encryption-only.12345=="
        val vehicle = File(context.noBackupFilesDir, "key-test.bin").apply { writeText("vehicle-key") }
        settings.save(" $secret ")
        assertTrue(settings.state.value.hasKey)
        assertFalse(File(context.noBackupFilesDir, "gemini-api-key.bin").readText().contains(secret))
        assertEquals(secret, GeminiSettings(context) { cipherKey }.apiKey())
        settings.delete()
        assertFalse(settings.state.value.hasKey)
        assertEquals("", GeminiSettings(context) { cipherKey }.apiKey())
        assertEquals("vehicle-key", vehicle.readText())
    }

    @Test fun invalidInputAndUnreadableCiphertextNeverSilentlyReplaceSavedKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipherKey = SecretKeySpec(ByteArray(32), "AES")
        val settings = GeminiSettings(context) { cipherKey }
        settings.save("local-test-key-that-is-long-enough")
        val file = File(context.noBackupFilesDir, "gemini-api-key.bin")
        val bytes = file.readBytes()
        try { settings.save("bad\nkey"); fail("invalid key accepted") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(bytes, file.readBytes())
        val wrongKey = GeminiSettings(context) { SecretKeySpec(ByteArray(32) { 1 }, "AES") }
        assertFalse(wrongKey.state.value.hasKey)
        assertNotNull(wrongKey.state.value.error)
        assertArrayEquals(bytes, file.readBytes())
    }
}
