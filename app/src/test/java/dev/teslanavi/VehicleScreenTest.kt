package dev.teslanavi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import dev.teslanavi.data.FeatureSettings
import dev.teslanavi.data.VehicleStore
import dev.teslanavi.protocol.BatterySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TeslaNaviApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleScreenTest {
    @Test fun settingsTabKeepsMultipleBluetoothSelectionAvailable() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        app.bluetoothStart.select(mapOf("00:11:22:33:44:55" to "Car A", "00:11:22:33:44:66" to "Car B"))
        app.features.setSettingsTab(true)
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithText("블루투스 자동 시작 설정").performScrollTo().performClick()
        compose.onNodeWithText("선택한 기기 2개").assertExists()
        compose.onNodeWithText("Car A").performClick()
        compose.onNodeWithText("저장").performClick()
        org.junit.Assert.assertEquals(mapOf("00:11:22:33:44:66" to "Car B"), app.bluetoothStart.state.value.selectedDevices)
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bluetoothStartListCanBeOpenedAndSelectionCleared() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<TeslaNaviApp>())
            .grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        compose.onNodeWithText("블루투스 자동 시작 설정").performScrollTo().performClick()
        compose.onNodeWithText("블루투스 자동 시작").assertExists()
        compose.onNodeWithText("휴대폰 Bluetooth 설정").assertExists()
        compose.onNodeWithText("전체 선택 해제 · 차량 접근으로 시작").performClick()
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("블루투스 자동 시작").assertDoesNotExist()
    }

    @Test fun multipleBluetoothChoicesAreSavedTogetherAndCancelPreservesSelection() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        app.bluetoothStart.select(mapOf("00:11:22:33:44:55" to "Car A", "00:11:22:33:44:66" to "Car B"))
        compose.onNodeWithText("블루투스 자동 시작 설정").performScrollTo().performClick()
        compose.onNodeWithText("선택한 기기 2개").assertExists()
        compose.onNodeWithText("Car A").performClick()
        compose.onNodeWithText("선택한 기기 1개").assertExists()
        compose.onNodeWithText("취소").performClick()
        org.junit.Assert.assertEquals(2, app.bluetoothStart.state.value.selectedDevices.size)
        compose.onNodeWithText("블루투스 자동 시작 설정").performScrollTo().performClick()
        compose.onNodeWithText("Car B").performClick()
        compose.onNodeWithText("Car A").performClick()
        compose.onNodeWithText("Car B").performClick()
        compose.onNodeWithText("저장").performClick()
        org.junit.Assert.assertEquals(mapOf("00:11:22:33:44:66" to "Car B"), app.bluetoothStart.state.value.selectedDevices)
    }

    @Test fun settingsButtonTurnsOnSettingsTabAndMovesVehicleControls() {
        compose.onNodeWithContentDescription("메인").assertDoesNotExist()
        compose.onNodeWithText("첫 차량을 등록해 주세요").assertIsDisplayed()
        compose.onNodeWithContentDescription("설정 열기").performClick()
        compose.onNodeWithContentDescription("하단 설정 탭").assertIsOff().performClick()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithContentDescription("설정 열기").assertDoesNotExist()
        compose.onNodeWithContentDescription("메인").assertIsSelected()
        compose.onNodeWithText("첫 차량을 등록해 주세요").assertDoesNotExist()
        compose.onNodeWithText("안내 설정").assertDoesNotExist()
        compose.onNodeWithText("차량을 등록해 주세요").assertIsDisplayed()
        saveScreenshot("settings-tab-main")
        compose.onNodeWithText("설정에서 차량 등록").performClick()
        compose.onNodeWithContentDescription("설정").assertIsSelected()
        // The feature card above now holds two switches, so the vehicle list may start below the fold.
        compose.onNodeWithText("첫 차량을 등록해 주세요").performScrollTo().assertIsDisplayed()
        saveScreenshot("settings-tab")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("메인").assertIsSelected()
        org.junit.Assert.assertTrue(FeatureSettings(ApplicationProvider.getApplicationContext()).settingsTab.value)
    }

    @Test fun turningSettingsTabOffRestoresMainControlsAndSettingsButton() {
        compose.runOnIdle { ApplicationProvider.getApplicationContext<TeslaNaviApp>().features.setSettingsTab(true) }
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithContentDescription("하단 설정 탭").assertIsOn().performClick()
        compose.onNodeWithContentDescription("메인").assertDoesNotExist()
        compose.onNodeWithContentDescription("설정 열기").assertIsDisplayed()
        compose.onNodeWithText("첫 차량을 등록해 주세요").assertIsDisplayed()
        compose.onNodeWithText("안내 설정").assertIsDisplayed()
    }

    @Test fun settingsTabOpensGuidanceAsPageAndBackReturnsToSettingsList() {
        compose.runOnIdle { ApplicationProvider.getApplicationContext<TeslaNaviApp>().features.setSettingsTab(true) }
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithText("안내 설정").performScrollTo().performClick()
        compose.onNodeWithContentDescription("뒤로").assertIsDisplayed()
        compose.onNodeWithText("안내 음성").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("닫기").assertDoesNotExist()
        compose.onNodeWithContentDescription("하단 설정 탭").assertDoesNotExist()
        compose.onNodeWithContentDescription("설정").assertIsSelected()
        saveScreenshot("settings-guidance-page")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("하단 설정 탭").assertIsDisplayed()
        compose.onNodeWithContentDescription("설정").assertIsSelected()
        compose.onNodeWithText("안내 설정").performScrollTo().performClick()
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithContentDescription("하단 설정 탭").assertIsDisplayed()
        compose.onNodeWithText("안내 설정").performScrollTo().performClick()
        compose.onNodeWithContentDescription("메인").performClick()
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithContentDescription("하단 설정 탭").assertIsDisplayed()
    }

    @Test fun soundChecksAreAvailableWithoutVehicleAndExplainZeroVolume() {
        compose.onNodeWithText("안내 설정").performClick()
        compose.onNodeWithText("카메라 음성 테스트").assertIsEnabled()
        compose.onNodeWithText("과속 경고음 테스트").assertIsEnabled()
        compose.onNodeWithContentDescription("안내 음량").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("카메라 음성 테스트").performScrollTo().performClick()
        compose.onNodeWithText("안내 음량이 0%입니다. 음량을 올린 뒤 테스트해 주세요.").assertExists()
        compose.onNodeWithText("과속 경고음 테스트").performScrollTo().performClick()
        compose.onNodeWithText("안내 음량이 0%입니다. 음량을 올린 뒤 테스트해 주세요.").assertExists()
    }

    @Test fun aiVoiceRequiresAnApiKeyWithNoModelDownload() {
        compose.onNodeWithText("안내 설정").performClick()
        compose.onNode(hasText("휴대폰 기본 음성") and isSelectable()).performScrollTo().assertIsSelected()
        compose.onNode(hasText("Gemini AI 음성") and isSelectable()).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Gemini API 키 등록").performScrollTo().assertIsEnabled()
    }

    @Test fun guidanceControlsSaveAndReopenAtSelectedValues() {
        compose.onNodeWithText("안내 설정").performClick()
        compose.onNodeWithContentDescription("안내 음량").performSemanticsAction(SemanticsActions.SetProgress) { it(35f) }
        compose.onNodeWithContentDescription("고속도로 안내 거리").performSemanticsAction(SemanticsActions.SetProgress) { it(1200f) }
        compose.onNodeWithContentDescription("일반도로 안내 거리").performSemanticsAction(SemanticsActions.SetProgress) { it(600f) }
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("안내 설정").performClick()
        compose.onNodeWithText("안내 음량 35%").assertIsDisplayed()
        saveScreenshot("guidance-settings")
        compose.onNodeWithText("고속도로 1200 m 전").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("일반도로 600 m 전").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("기본값 복원").performScrollTo().performClick()
        compose.onNodeWithText("고속도로 500 m 전").performScrollTo().assertIsDisplayed()
    }

    @Test fun logbookTabAppearsOnlyWhenEnabledAndShowsEfficiencyAndCostPerKm() {
        ApplicationProvider.getApplicationContext<TeslaNaviApp>().vehicles.add("Model Y", "5YJYGDEE1NF123456")
        compose.onNodeWithContentDescription("통계").assertDoesNotExist()
        compose.onNodeWithContentDescription("메인").assertDoesNotExist()
        openLogbook()
        // Logbook without the settings tab: two icons, and the logbook header keeps the settings button.
        compose.onNodeWithContentDescription("통계").assertIsSelected()
        compose.onNodeWithContentDescription("메인").assertIsNotSelected()
        compose.onNodeWithContentDescription("설정").assertDoesNotExist()
        compose.onNodeWithContentDescription("설정 열기").assertIsDisplayed()
        compose.onNodeWithText("첫 충전을 기록해 주세요").assertIsDisplayed()
        addCharge("10000", "40")
        compose.onNodeWithText("다음 충전을 기록하면 전비를 계산합니다").assertIsDisplayed()
        addCharge("10228", "30")
        compose.onAllNodesWithText("7.6").assertCountEquals(2)
        compose.onNodeWithText("38.8").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("원/km").assertIsDisplayed()
        compose.onNodeWithText("주행 228 km · 충전 30.0 kWh").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("이번 달 · 충전 70.0 kWh · 약 20,650원").performScrollTo().assertIsDisplayed()
        saveScreenshot("logbook")
        compose.onNodeWithText("기본 충전 단가 295원/kWh").performScrollTo().assertIsDisplayed()
        val month = java.time.YearMonth.now()
        compose.onNodeWithText("${month.year}년 ${month.monthValue}월").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2회 · 70.0 kWh · 약 20,650원").assertIsDisplayed()
        compose.onNodeWithText("38.8원/km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+228 km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10,228 km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("기준 기록").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("40.0 kWh").performScrollTo().assertIsDisplayed()
        saveScreenshot("logbook-history")
        compose.onNodeWithContentDescription("메인").performClick()
        compose.onNodeWithText("내 차의 목적지").assertIsDisplayed()
        compose.onNodeWithText("안내 설정").assertIsDisplayed()
        compose.onNodeWithContentDescription("설정 열기").performClick()
        compose.onNodeWithContentDescription("통계 탭").assertIsOn().performClick()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithContentDescription("통계").assertDoesNotExist()
        compose.onNodeWithContentDescription("메인").assertDoesNotExist()
        compose.onNodeWithText("내 차의 목적지").assertIsDisplayed()
    }

    @Test fun logbookAndSettingsTabsShareOneBar() {
        compose.onNodeWithContentDescription("설정 열기").performClick()
        compose.onNodeWithContentDescription("하단 설정 탭").performClick()
        compose.onNodeWithContentDescription("통계 탭").performClick()
        compose.onNodeWithText("닫기").performClick()
        listOf("메인", "통계", "설정").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("설정 열기").assertDoesNotExist()
        compose.onNodeWithContentDescription("통계").performClick()
        compose.onNodeWithText("통계").assertIsDisplayed()
        compose.onNodeWithContentDescription("설정 열기").assertDoesNotExist()
        saveScreenshot("logbook-three-tabs")
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithContentDescription("통계 탭").assertIsOn().performClick()
        compose.onNodeWithContentDescription("통계").assertDoesNotExist()
        compose.onNodeWithContentDescription("설정").assertIsSelected()
        org.junit.Assert.assertTrue(ApplicationProvider.getApplicationContext<TeslaNaviApp>().features.settingsTab.value)
    }

    @Test fun driveSegmentShowsTripsMonthTotalsAndUnrecordedDistance() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        app.vehicles.add("Model Y", "5YJYGDEE1NF123456")
        val zone = java.time.ZoneId.systemDefault()
        fun at(hour: Int, minute: Int) = java.time.LocalDate.now(zone).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        app.trips.add("5YJYGDEE1NF123456", dev.teslanavi.logbook.DriveTrip("a", at(8, 12), at(8, 47), 10_000.0, 10_032.4, 32.4, 98.0, 80, 74))
        app.trips.add("5YJYGDEE1NF123456", dev.teslanavi.logbook.DriveTrip("b", at(18, 0), at(18, 25), 10_050.0, 10_070.6, 20.6, 72.0, 74, 70))
        openLogbook()
        compose.onNodeWithText("이번 달 · 주행 53 km").assertIsDisplayed()
        compose.onNodeWithText("첫 충전을 기록해 주세요").assertIsDisplayed()
        compose.onNodeWithText("주행").performClick()
        compose.onNodeWithText("20.6").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("72 km/h").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("49 km/h").assertCountEquals(2)
        compose.onAllNodesWithText("-4%").assertCountEquals(2)
        saveScreenshot("stats-drive")
        compose.onNodeWithText("2회 · 53 km · 1시간").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("18:00 → 18:25").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("20").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("32").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("기록되지 않은 주행 약 18 km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("08:12 → 08:47").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("-6%").performScrollTo().assertIsDisplayed()
        saveScreenshot("stats-drive-history")
        compose.onNodeWithContentDescription("주행 기록 안내").performScrollTo().performClick()
        compose.onNodeWithText("카메라 안내와 같은 기준입니다", substring = true).assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        compose.onAllNodesWithContentDescription("주행 기록 메뉴")[1].performScrollTo().performClick()
        compose.onNodeWithText("기록 삭제").performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithText("08:12 → 08:47").assertDoesNotExist()
        org.junit.Assert.assertEquals(listOf("b"), app.trips.trips.value["5YJYGDEE1NF123456"]!!.map { it.id })
    }

    @Test fun tripsUnderOneKilometerShowHundredMeterSteps() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        app.vehicles.add("Model Y", "5YJYGDEE1NF123456")
        val start = System.currentTimeMillis() - 600_000
        app.trips.add("5YJYGDEE1NF123456", dev.teslanavi.logbook.DriveTrip("a", start, start + 180_000, 100.0, 100.87, 0.87, 35.0, null, null))
        openLogbook()
        compose.onNodeWithText("주행").performClick()
        compose.onNodeWithText("0.9").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("800").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("m").performScrollTo().assertIsDisplayed()
    }

    @Test fun rateChangeInLogbookRecalculatesCostPerKm() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        app.vehicles.add("Model Y", "5YJYGDEE1NF123456")
        app.logbook.add("5YJYGDEE1NF123456", 10_000.0, 40.0)
        app.logbook.add("5YJYGDEE1NF123456", 10_228.0, 30.0)
        openLogbook()
        compose.onNodeWithText("단가 변경").performScrollTo().performClick()
        compose.onNodeWithText("기본 충전 단가 (원/kWh)").performTextReplacement("347")
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("기본 충전 단가 347원/kWh").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("45.7").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(347, app.logbookSettings.wonPerKwh.value)
    }

    @Test fun perChargeRateFromMenuRepricesOnlyThatCharge() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        app.vehicles.add("Model Y", "5YJYGDEE1NF123456")
        app.logbook.add("5YJYGDEE1NF123456", 10_000.0, 40.0)
        app.logbook.add("5YJYGDEE1NF123456", 10_228.0, 30.0)
        openLogbook()
        compose.onNodeWithText("직접").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("충전 기록 메뉴")[0].performScrollTo().performClick()
        compose.onNodeWithText("충전단가 수정").performClick()
        compose.onNodeWithText("이 충전의 단가 (원/kWh)").performTextReplacement("347")
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("45.7").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("7.6").assertCountEquals(2)
        compose.onNodeWithText("이번 달 · 충전 70.0 kWh · 약 22,210원").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("45.7원/km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2회 · 70.0 kWh · 약 22,210원").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("기본 충전 단가 295원/kWh").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(listOf(null, 347), app.logbook.entries.value["5YJYGDEE1NF123456"]!!.map { it.wonPerKwh })
        compose.onAllNodesWithContentDescription("충전 기록 메뉴")[0].performScrollTo().performClick()
        compose.onNodeWithText("충전단가 수정").performClick()
        compose.onNodeWithText("기본 단가(295원)로 되돌리기").performClick()
        compose.onNodeWithText("38.8원/km").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertNull(app.logbook.entries.value["5YJYGDEE1NF123456"]!![1].wonPerKwh)
    }

    @Test fun chargeInfoOpensInDialogAndMenuDeletesOnlyThatRecord() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        app.vehicles.add("Model Y", "5YJYGDEE1NF123456")
        app.logbook.add("5YJYGDEE1NF123456", 10_000.0, 40.0)
        app.logbook.add("5YJYGDEE1NF123456", 10_228.0, 30.0)
        openLogbook()
        compose.onNodeWithText("차량이 연결된 동안", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("충전 기록 안내").performScrollTo().performClick()
        compose.onNodeWithText("전비는 이전 충전 이후 주행거리를 이번 충전량으로 나눈 값입니다.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        compose.onAllNodesWithContentDescription("충전 기록 메뉴")[0].performScrollTo().performClick()
        compose.onNodeWithText("기록 삭제").performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithText("+228 km").assertDoesNotExist()
        compose.onNodeWithText("다음 충전을 기록하면 전비를 계산합니다").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(listOf(10_000.0), app.logbook.entries.value["5YJYGDEE1NF123456"]!!.map { it.odometerKm })
    }

    @Test fun eachVehicleKeepsItsOwnLogbookChosenInsideTheTab() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        app.vehicles.add("Model 3", "5YJ3E1EA1MF111111")
        app.vehicles.add("Model Y", "5YJYGDEE1NF222222")
        app.logbook.add("5YJ3E1EA1MF111111", 10_000.0, 40.0)
        app.logbook.add("5YJ3E1EA1MF111111", 10_228.0, 30.0)
        openLogbook()
        compose.onNodeWithText("첫 충전을 기록해 주세요").assertIsDisplayed()
        compose.onNodeWithText("Model 3 · 111111").performClick()
        compose.onNodeWithText("38.8").performScrollTo().assertIsDisplayed()
        saveScreenshot("logbook-vehicles")
        addCharge("10500", "34")
        compose.onNodeWithText("Model Y · 222222").performScrollTo().performClick()
        compose.onNodeWithText("첫 충전을 기록해 주세요").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(3, app.logbook.entries.value["5YJ3E1EA1MF111111"]!!.size)
        org.junit.Assert.assertNull(app.logbook.entries.value["5YJYGDEE1NF222222"])
        org.junit.Assert.assertEquals("5YJYGDEE1NF222222", app.vehicles.selected.value)
    }

    @Test fun logbookAsksForVehicleBeforeRecordingCharges() {
        openLogbook()
        compose.onNodeWithText("차량을 먼저 등록해 주세요").assertIsDisplayed()
        compose.onNodeWithText("+ 충전 기록").assertDoesNotExist()
    }

    private fun openLogbook() {
        compose.onNodeWithContentDescription("설정 열기").performClick()
        compose.onNodeWithContentDescription("통계 탭").assertIsOff().performClick()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithContentDescription("통계").performClick()
    }

    private fun addCharge(odometer: String, energy: String) {
        compose.onNodeWithText("+ 충전 기록").performScrollTo().performClick()
        compose.onNodeWithText("누적 주행거리 (km)").performTextInput(odometer)
        compose.onNodeWithText("충전량 (kWh)").performTextInput(energy)
        compose.onNodeWithText("저장").performClick()
    }

    @Test fun firstLaunchShowsRegistrationAndDisabledConnection() {
        compose.onNodeWithText("첫 차량을 등록해 주세요").assertIsDisplayed()
        compose.onNode(hasText("차량 연결") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("처음 연결하나요? 앱 키 등록").assertIsNotEnabled()
        compose.onNodeWithContentDescription("자동 연결").assertIsNotEnabled()
        saveScreenshot("first-launch")
    }

    @Test fun registersModelYAndShowsOnlyMaskedVinInList() {
        compose.onNodeWithText("차량 등록하기").performClick()
        compose.onNodeWithText("Model Y").performClick()
        compose.onNodeWithText("차대번호(VIN) 17자리").performTextInput("5YJYGDEE1NF123456")
        compose.onNodeWithText("등록", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Model Y").assertIsDisplayed()
        compose.onNodeWithText("••• 123456  ✓").assertIsDisplayed()
        compose.onNodeWithText("처음 연결하나요? 앱 키 등록").assertIsEnabled()
        saveScreenshot("vehicle-registered")
    }

    @Test fun malformedVinStaysInDialogWithoutSavingVehicle() {
        compose.onNodeWithText("차량 등록하기").performClick()
        compose.onNodeWithText("차대번호(VIN) 17자리").performTextInput("INVALID")
        compose.onNodeWithText("등록", useUnmergedTree = true).performClick()
        compose.onNodeWithText("VIN은 I·O·Q를 제외한 영문과 숫자 17자리입니다.").assertIsDisplayed()
    }

    @Test fun selectedVehiclePersistsAndDuplicateVinDoesNotCreateSecondRecord() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        app.vehicles.add("Model 3", "5YJ3E1EA1MF111111")
        app.vehicles.add("Model Y", "5YJYGDEE1NF222222")
        app.vehicles.add("Model Y", "5YJYGDEE1NF222222")
        app.vehicles.select("5YJ3E1EA1MF111111")
        val reloaded = VehicleStore(app)
        org.junit.Assert.assertEquals(2, reloaded.vehicles.value.size)
        org.junit.Assert.assertEquals("5YJ3E1EA1MF111111", reloaded.selected.value)
    }

    @Test fun lastBatteryReadingShowsWithAgeOnBothMainLayouts() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        compose.runOnIdle {
            app.vehicles.add("Model Y", "5YJYGDEE1NF123456")
            app.drive.recordBattery("5YJYGDEE1NF123456", BatterySnapshot(78, 352.4), System.currentTimeMillis() - 12 * 60_000)
        }
        compose.onNodeWithContentDescription("배터리 78% · 주행 가능 352 km").assertIsDisplayed()
        compose.onNodeWithText("78%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("352 km", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("마지막 수신 12분 전", useUnmergedTree = true).assertIsDisplayed()
        saveScreenshot("battery-main")
        compose.runOnIdle { app.features.setSettingsTab(true) }
        compose.onNodeWithContentDescription("메인").assertIsSelected()
        compose.onNodeWithContentDescription("배터리 78% · 주행 가능 352 km").assertIsDisplayed()
        compose.onNodeWithText("마지막 수신 12분 전", useUnmergedTree = true).assertIsDisplayed()
        saveScreenshot("battery-settings-tab-main")
    }

    @Test fun batteryLineIsHiddenWithoutReadingForSelectedVehicle() {
        val app = ApplicationProvider.getApplicationContext<TeslaNaviApp>()
        compose.runOnIdle {
            app.vehicles.add("Model 3", "5YJ3E1EA1MF111111")
            app.vehicles.add("Model Y", "5YJYGDEE1NF222222")
        }
        compose.onNodeWithText("배터리", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { app.drive.recordBattery("5YJYGDEE1NF222222", BatterySnapshot(64, null)) }
        compose.onNodeWithContentDescription("배터리 64%").assertIsDisplayed()
        compose.onNodeWithText("주행 가능", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("마지막 수신 방금", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { app.vehicles.select("5YJ3E1EA1MF111111") }
        compose.onNodeWithText("배터리", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { app.drive.recordBattery("5YJ3E1EA1MF111111", BatterySnapshot(null, null)) }
        compose.onNodeWithText("배터리", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun saveScreenshot(name: String) {
        val directory = File("build/reports/screenshots").apply { mkdirs() }
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
