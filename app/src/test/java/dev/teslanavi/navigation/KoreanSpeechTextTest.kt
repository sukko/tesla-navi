package dev.teslanavi.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanSpeechTextTest {
    @Test fun allTwelveSpeedLimitsUseTheRequestedReadings() {
        listOf("십", "이십", "삼십", "사십", "오십", "육십", "칠십", "팔십", "구십", "백", "백십", "백이십")
            .forEachIndexed { index, spoken ->
                assertEquals("제한속도 ${spoken} 킬로미터 초과입니다.",
                    KoreanSpeechText.normalize("제한속도 ${(index + 1) * 10}킬로미터 초과입니다."))
            }
    }
    @Test fun guidanceNumbersUseSinoKoreanWithoutLeadingOne() {
        mapOf("0" to "영", "5" to "오", "10" to "십", "11" to "십일", "30" to "삼십", "50" to "오십", "60" to "육십",
            "100" to "백", "110" to "백십", "300" to "삼백", "1000" to "천", "1200" to "천이백", "2000" to "이천",
            "10000" to "만", "15000" to "만오천", "20050" to "이만오십", "100000000" to "일억", "007" to "칠",
        ).forEach { (digits, expected) -> assertEquals(digits, expected, KoreanSpeechText.number(digits)) }
    }

    @Test fun announcementsKeepWordingAndSpellOutEveryNumber() {
        assertEquals("삼백 미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 삼십 킬로미터입니다.",
            KoreanSpeechText.normalize("300미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 30킬로미터입니다."))
        assertEquals("제한속도 백십 킬로미터 초과입니다.", KoreanSpeechText.normalize("제한속도 110킬로미터 초과입니다."))
        assertEquals("천이백 미터 앞 이동식 단속 카메라가 있습니다.", KoreanSpeechText.normalize("1200미터 앞 이동식 단속 카메라가 있습니다."))
    }

    @Test fun middleDotSeparatorsDecimalsAndGroupingAreSpeakable() {
        assertEquals("잠시 후 신호 과속 단속 카메라가 있습니다.", KoreanSpeechText.normalize("잠시 후 신호·과속 단속 카메라가 있습니다."))
        assertEquals("일 점 오 킬로미터", KoreanSpeechText.normalize("1.5킬로미터"))
        assertEquals("천이백 미터", KoreanSpeechText.normalize("1,200미터"))
        assertEquals("주행시작", KoreanSpeechText.normalize("주행시작"))
    }

    @Test fun repeatedNormalizationKeepsUnitSpacingStableAcrossSpeakerAndRenderer() {
        val expected = "삼백 미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 오십 킬로미터입니다."
        val normalized = KoreanSpeechText.normalize("300미터 앞 과속 단속 카메라가 있습니다. 제한속도 시속 50킬로미터입니다.")
        assertEquals(expected, normalized)
        assertEquals(expected, KoreanSpeechText.normalize(normalized))
        assertEquals("제한속도 백이십 킬로미터 초과입니다.", KoreanSpeechText.normalize("제한속도 백이십킬로미터 초과입니다."))
    }
}
