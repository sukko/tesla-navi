package dev.teslanavi.navigation

/**
 * Shared text for system and Gemini speech. Raw digits can be read inconsistently,
 * so numbers are spelled out in Sino-Korean, units are separated, and separators become spaces.
 */
object KoreanSpeechText {
    private const val DIGITS = "영일이삼사오육칠팔구"
    private val decimal = Regex("""(\d+)\.(\d+)""")
    private val grouped = Regex("""\d{1,3}(,\d{3})+""")
    private val integer = Regex("""\d+""")
    private val measurement = Regex("""([영일이삼사오육칠팔구십백천만억조])((?:킬로)?미터)""")

    fun normalize(text: String): String = text
        .replace(grouped) { it.value.replace(",", "") }
        .replace(decimal) { "${number(it.groupValues[1])} 점 ${it.groupValues[2].map { d -> DIGITS[d - '0'] }.joinToString("")}" }
        .replace(integer) { number(it.value) }
        .replace(measurement) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        .replace('·', ' ')
        .replace(Regex(" {2,}"), " ")
        .trim()

    /** 0 → 영, 110 → 백십, 1200 → 천이백, 15000 → 만오천. Values beyond 10^16 keep digit-by-digit reading. */
    fun number(digits: String): String {
        val value = digits.trimStart('0').ifEmpty { return "영" }
        if (value.length > 16) return value.map { DIGITS[it - '0'] }.joinToString("")
        var rest = value.toLong()
        val out = StringBuilder()
        for ((unit, size) in listOf("조" to 1_000_000_000_000L, "억" to 100_000_000L, "만" to 10_000L, "" to 1L)) {
            val group = (rest / size).toInt()
            rest %= size
            if (group == 0) continue
            out.append(if (group == 1 && unit == "만") "" else belowTenThousand(group)).append(unit)
        }
        return out.toString()
    }

    private fun belowTenThousand(n: Int): String = buildString {
        for ((unit, size) in listOf("천" to 1000, "백" to 100, "십" to 10, "" to 1)) {
            val d = n / size % 10
            if (d == 0) continue
            if (d > 1 || unit.isEmpty()) append(DIGITS[d])
            append(unit)
        }
    }
}
