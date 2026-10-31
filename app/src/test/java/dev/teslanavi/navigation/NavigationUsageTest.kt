package dev.teslanavi.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationUsageTest {
    @Test fun countsRouteRequestsSeparatelyFromSdkChecksAndUidTraffic() {
        val usage = NavigationUsage()
        usage.start(0, 10_240, 20_480)
        usage.routeRequested(); repeat(3) { usage.sdkCheckingRoute() }
        assertNull(usage.report(299_999, 100_000, 100_000))
        val line = usage.report(300_000, 12_288, 21_504)!!
        assertTrue(line.contains("앱 경로 탐색 1회 · SDK 경로 확인 3회"))
        assertTrue(line.contains("앱 전체 수신 2 KiB · 송신 1 KiB"))
        assertFalse(usage.due(300_001))
        val end = usage.report(600_000, 14_336, 23_552, end = true)!!
        assertTrue(end.contains("종료 10분"))
        assertTrue(end.contains("수신 4 KiB · 송신 3 KiB"))
        assertNull(usage.report(900_000, 0, 0, end = true))
    }

    @Test fun unsupportedOrResetTrafficCountersAreNotShownAsZeroOrNegativeUsage() {
        for ((start, end) in listOf(-1L to -1L, 100L to 90L)) {
            val usage = NavigationUsage()
            usage.start(0, start, start)
            assertTrue(usage.report(1000, end, end, end = true)!!.contains("통신량 확인 불가"))
        }
    }
}
