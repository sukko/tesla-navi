package dev.teslanavi.navigation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.MainActivity
import dev.teslanavi.TeslaNaviApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class, qualifiers = "w384dp-h832dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeminiVoiceChoiceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun missingKeyKeepsRegistrationAvailableAndMasksInputWhileShowingValidationErrors() {
        compose.runOnIdle { ApplicationProvider.getApplicationContext<TeslaNaviApp>().features.setSettingsTab(true) }
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithText("안내 설정").performScrollTo().performClick()
        compose.onNode(hasText("Gemini AI 음성") and isSelectable()).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Gemini API 키 등록").performScrollTo().performClick()
        compose.onNodeWithText("저장·사용").assertIsNotEnabled()
        compose.onNodeWithText("API 키 입력").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("short")) }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText("저장·사용").performClick()
        compose.onNodeWithText("API 키를 공백 없이 정확하게 입력해 주세요.").assertExists()
        compose.onNodeWithText("취소").performClick()
        compose.onNode(hasText("Gemini AI 음성") and isSelectable()).assertIsNotEnabled()
    }
    @Test fun voiceAndSpeedCanBeChangedAndPersistWithoutCloudRequests() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        compose.runOnIdle {
            app.features.setSettingsTab(true)
            // Android Keystore is covered separately; this UI test supplies key availability only.
            val state = org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.flow.MutableStateFlow<GeminiKeyState>>(app.geminiSettings, "mutable")
            state.value = GeminiKeyState(hasKey = true)
            app.selectVoice(GuidanceVoice.GEMINI)
        }
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithText("안내 설정").performScrollTo().performClick()
        compose.onNodeWithText("목소리 변경 · Kore · 단단한").performScrollTo().performClick()
        compose.onNodeWithText("Zephyr · 밝은").performClick()
        compose.onNodeWithText("목소리 변경 · Zephyr · 밝은").assertExists()
        compose.onNodeWithContentDescription("Gemini 말하기 속도").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(140f) }
        compose.runOnIdle {
            val reloaded = GuidanceSettings(app).options.value
            assertEquals(GeminiSpeaker.Zephyr, reloaded.geminiSpeaker)
            assertEquals(140, reloaded.geminiSpeedPercent)
        }
    }

}
