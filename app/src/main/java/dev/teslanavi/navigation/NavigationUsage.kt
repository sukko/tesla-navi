package dev.teslanavi.navigation

/** Per-guidance counters. UID bytes cover the whole app, not just the SDK or billable mobile data. */
internal class NavigationUsage(private val intervalMillis: Long = 300_000L) {
    private var startAt: Long? = null
    private var reportedAt = 0L
    private var initialRx = -1L
    private var initialTx = -1L
    private var initialRoutes = 0
    private var sdkChecks = 0

    fun start(now: Long, rx: Long, tx: Long) {
        startAt = now; reportedAt = now; initialRx = rx; initialTx = tx
        initialRoutes = 0; sdkChecks = 0
    }
    fun routeRequested() { if (startAt != null) initialRoutes++ }
    fun sdkCheckingRoute() { if (startAt != null) sdkChecks++ }
    fun due(now: Long) = startAt != null && now - reportedAt >= intervalMillis

    fun report(now: Long, rx: Long, tx: Long, end: Boolean = false): String? {
        val start = startAt ?: return null
        if (!end && !due(now)) return null
        reportedAt = now
        if (end) startAt = null
        val data = if (initialRx >= 0 && initialTx >= 0 && rx >= initialRx && tx >= initialTx)
            "앱 전체 수신 ${(rx - initialRx) / 1024} KiB · 송신 ${(tx - initialTx) / 1024} KiB"
            else "앱 통신량 확인 불가"
        return "사용량 ${if (end) "종료" else "누적"} ${(now - start).coerceAtLeast(0) / 60_000}분 · " +
            "앱 경로 탐색 ${initialRoutes}회 · SDK 경로 확인 ${sdkChecks}회 · $data"
    }
}
