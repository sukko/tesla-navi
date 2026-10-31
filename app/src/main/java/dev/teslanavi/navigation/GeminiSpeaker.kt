package dev.teslanavi.navigation

/** Prebuilt studio voice IDs from Google's speech-generation documentation. */
enum class GeminiSpeaker(val description: String) {
    Kore("단단한"), Zephyr("밝은"), Puck("경쾌한"), Charon("설명하는"),
    Fenrir("활기찬"), Leda("젊은"), Orus("단단한"), Aoede("산뜻한"),
    Callirrhoe("편안한"), Autonoe("밝은"), Enceladus("숨결이 느껴지는"), Iapetus("명료한"),
    Umbriel("편안한"), Algieba("매끄러운"), Despina("매끄러운"), Erinome("명료한"),
    Algenib("거친"), Rasalgethi("설명하는"), Laomedeia("경쾌한"), Achernar("부드러운"),
    Alnilam("단단한"), Schedar("고른"), Gacrux("성숙한"), Pulcherrima("힘 있는"),
    Achird("친근한"), Zubenelgenubi("자연스러운"), Vindemiatrix("온화한"),
    Sadachbia("생동감 있는"), Sadaltager("지적인"), Sulafat("따뜻한");

    val label get() = "$name · $description"
}
