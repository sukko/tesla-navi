package dev.teslanavi.protocol

import com.tesla.generated.carserver.common.LatLong
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.ResultReason
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.LocationState
import com.tesla.generated.carserver.vehicle.ShiftState
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.keys.Role
import com.tesla.generated.signatures.*
import com.tesla.generated.universalmessage.*
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.WhitelistOperation_status
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import java.math.BigInteger
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import kotlin.test.*

private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private const val VIN = "5YJ3E1EA1MF123456"

class ProtocolTest {
    @Test fun `parked drive-only read does not request location or reuse its old coordinates`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        vehicle.nextLocation = LocationState.newBuilder().setLatitude(37.5f).setLongitude(127f).build()
        assertNotNull(client.readDriveState().location)
        vehicle.nextState = DriveState.newBuilder().setShiftState(ShiftState.newBuilder().setP(Void.getDefaultInstance())).build()
        assertNull(client.readDriveState(includeLocation = false).location)
        assertNull(client.locationRejection)
        assertEquals(listOf("drive", "location", "drive"), vehicle.queries)
        assertNotNull(client.readDriveState().location)
        assertEquals(listOf("drive", "location", "drive", "drive", "location"), vehicle.queries)
    }
    private fun enrollmentReply(code: Int) = FromVCSECMessage.newBuilder().setCommandStatus(
        CommandStatus.newBuilder().setOperationStatusValue(2).setWhitelistOperationStatus(
            WhitelistOperation_status.newBuilder().setWhitelistOperationInformationValue(code)
        )
    ).build().toByteArray()

    @Test fun `card timeout is reported during enrollment instead of ignored`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.requestKeyEnrollment()
        vehicle.incoming.send(enrollmentReply(25))
        val error = assertFailsWith<EnrollmentRejectedException> { client.authenticate() }
        assertEquals(25, error.code)
        assertTrue(error.message!!.contains("키 카드"))
    }

    @Test fun `all enrollment rejections retain code and existing key can authenticate`() = runTest {
        for (code in listOf(3, 4, 5, 6, 14, 23, 24, 25, 26, 27, 28, 99)) {
            assertEquals(code, enrollmentFailure(enrollmentReply(code))!!.code)
        }
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.requestKeyEnrollment()
        vehicle.incoming.send(enrollmentReply(13))
        client.authenticate()
        client.readDriveState()
    }

    @Test fun `raw enrollment diagnostics do not affect normal authenticated traffic`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        vehicle.incoming.send(enrollmentReply(24))
        client.authenticate()
        client.requestKeyEnrollment()
        client.authenticate()
        vehicle.incoming.send(enrollmentReply(24))
        client.readDriveState()
    }

    @Test fun `enrollment OK never bypasses signed authentication`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys).apply { corruptHandshake = true }
        val client = TeslaClient(VIN, keys, vehicle)
        client.requestKeyEnrollment()
        vehicle.incoming.send(enrollmentReply(0))
        assertFailsWith<IllegalArgumentException> { client.authenticate() }
    }

    @Test fun `routed VCSEC diagnostic must target this connection`() = runTest {
        for (wrongAddress in listOf(false, true)) {
            val keys = TeslaKeys.generate()
            val vehicle = FakeVehicle(keys).apply {
                beforeHandshake = { request ->
                    RoutableMessage.newBuilder()
                        .setFromDestination(Destination.newBuilder().setDomain(Domain.DOMAIN_VEHICLE_SECURITY))
                        .setToDestination(if (wrongAddress) Destination.newBuilder().setRoutingAddress(ByteArray(16).bytes()).build() else request.fromDestination)
                        .setProtobufMessageAsBytes(enrollmentReply(24).bytes()).build().toByteArray()
                }
            }
            val client = TeslaClient(VIN, keys, vehicle)
            client.requestKeyEnrollment()
            if (wrongAddress) client.authenticate()
            else assertEquals(24, assertFailsWith<EnrollmentRejectedException> { client.authenticate() }.code)
        }
    }

    @Test fun `gear field distinguishes park from missing shift state`() {
        val park = ShiftState.newBuilder().setP(com.tesla.generated.carserver.common.Void.getDefaultInstance()).build()
        val drive = ShiftState.newBuilder().setD(com.tesla.generated.carserver.common.Void.getDefaultInstance()).build()
        assertEquals(Gear.PARK, DriveSnapshot.from(DriveState.newBuilder().setShiftState(park).build()).gear)
        assertEquals(Gear.DRIVE, DriveSnapshot.from(DriveState.newBuilder().setShiftState(drive).build()).gear)
        assertEquals(Gear.UNKNOWN, DriveSnapshot.from(DriveState.getDefaultInstance()).gear)
    }
    @Test fun `metadata and AES GCM match Tesla published test vector`() {
        // Tesla protocol_doc_test.go; protects against self-consistent but incompatible crypto.
        val epoch = "4c463f9cc0d3d26906e982ed224adde6".hex()
        val encoded = metadata(0 to byteArrayOf(5), 1 to byteArrayOf(3),
            2 to "5YJ30123456789ABC".toByteArray(), 3 to epoch,
            4 to uint32(2655), 5 to uint32(7), 7 to uint32(2))
        assertContentEquals("000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007070400000002ff".hex(), encoded)
        val sealed = TeslaSession.crypt(Cipher.ENCRYPT_MODE,
            "1b2fce19967b79db696f909cff89ea9a".hex(), "dbf79447fa156674dae1caed".hex(),
            "120452020801".hex(), MessageDigest.getInstance("SHA-256").digest(encoded))
        assertContentEquals("38038e8c0f2ec228e0ff64991481db3a7bbc133696c5".hex(), sealed)
    }

    @Test fun `ECDH preserves leading zero in Tesla shared secret vector`() {
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(
            BigInteger(1, "5260f8d6113875d86f8ee8fea340df1ffb40c658b5455e8c33d797c53a41afd3".hex()), parameters))
        val remote = "0407fb60b65b94e0de4a954c53be10003d9e69918dedfda5f4e9efb9ebd8c5bd672a53991c4068865d5fb44f97f6cecf8398f261dd1d7bc69be676afdc8ffacbcc".hex()
        val expected = MessageDigest.getInstance("SHA-1").digest("0072d5b815207a04f0c795fba0ba9e8add3f1f57148c51fface22ca15e6fd845".hex()).copyOf(16)
        assertContentEquals(expected, TeslaKeys.sharedKey(privateKey, remote))
        assertFails { TeslaKeys.sharedKey(privateKey, ByteArray(65).also { it[0] = 4 }) }
    }

    @Test fun `VIN normalization rejects ambiguous letters and produces deterministic beacon`() {
        assertEquals(VIN, VehicleIdentity.normalizeVin(" 5yj3e1ea1mf123456 "))
        assertFalse(VehicleIdentity.isValidVin("5YJ3E1EAIMF123456"))
        assertFalse(VehicleIdentity.isValidVin("123"))
        assertTrue(VehicleIdentity.localName(VIN).matches(Regex("S[0-9a-f]{16}C")))
        assertNotEquals(VehicleIdentity.localName(VIN), VehicleIdentity.localName("5YJYGDEE1NF123456"))
    }

    @Test fun `BLE reassembles arbitrary splits and multiple frames`() {
        val first = ByteArray(61) { it.toByte() }
        val second = byteArrayOf(9, 8, 7)
        val bytes = (BleFraming.encode(first, 23) + BleFraming.encode(second, 23)).fold(byteArrayOf()) { a, b -> a + b }
        for (split in 1 until bytes.size) {
            val parser = BleFraming()
            val actual = parser.accept(bytes.copyOfRange(0, split), 1) + parser.accept(bytes.copyOfRange(split, bytes.size), 2)
            assertEquals(2, actual.size)
            assertContentEquals(first, actual[0]); assertContentEquals(second, actual[1])
        }
    }

    @Test fun `BLE discards incomplete expired frame and rejects excessive length`() {
        val parser = BleFraming()
        assertTrue(parser.accept(byteArrayOf(0, 40, 1, 2), 0).isEmpty())
        assertContentEquals(byteArrayOf(7), parser.accept(byteArrayOf(0, 1, 7), 1001).single())
        assertFailsWith<IllegalArgumentException> { parser.accept(byteArrayOf(4, 1), 1002) }
        assertContentEquals(byteArrayOf(8), parser.accept(byteArrayOf(0, 1, 8), 1003).single())
    }

    @Test fun `coordinates at zero and missing optional speed are handled correctly`() {
        val state = DriveState.newBuilder().setActiveRouteCoordinates(LatLong.newBuilder().setLatitude(0f).setLongitude(0f)).build()
        val snapshot = DriveSnapshot.from(state)
        assertEquals(GeoPoint(0.0, 0.0), (snapshot.destination as NavigationDestination.Active).point)
        assertNull(snapshot.speedKph)
        assertEquals(NavigationDestination.None, DriveSnapshot.from(DriveState.getDefaultInstance()).destination)
    }

    @Test fun `key enrollment requests monitor role and never marks transmission as approval`() = runTest {
        val keys = TeslaKeys.generate()
        val transport = FakeVehicle(keys)
        TeslaClient(VIN, keys, transport).requestKeyEnrollment()
        val envelope = ToVCSECMessage.parseFrom(transport.rawEnrollment)
        assertEquals(2, envelope.signedMessage.signatureTypeValue)
        val request = UnsignedMessage.parseFrom(envelope.signedMessage.protobufMessageAsBytes)
        assertEquals(Role.ROLE_VEHICLE_MONITOR, request.whitelistOperation.addKeyToWhitelistAndAddPermissions.keyRole)
        assertContentEquals(TeslaKeys.publicBytes(keys.public), request.whitelistOperation.addKeyToWhitelistAndAddPermissions.key.publicKeyRaw.toByteArray())
    }

    @Test fun `signed handshake encrypted lookup destination change and cancellation`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        vehicle.nextState = DriveState.newBuilder().setActiveRouteDestination("서울역")
            .setActiveRouteCoordinates(LatLong.newBuilder().setLatitude(37.555f).setLongitude(126.971f))
            .setSpeedFloat(50f).setActiveRouteMilesToArrival(10f).build()
        val first = client.readDriveState()
        assertEquals("서울역", (first.destination as NavigationDestination.Active).name)
        assertEquals(80.4672, first.speedKph!!, 0.0001)
        vehicle.nextState = DriveState.newBuilder().setActiveRouteDestination("부산역").build()
        assertEquals("부산역", (client.readDriveState().destination as NavigationDestination.Active).name)
        vehicle.nextState = DriveState.newBuilder().setSpeed(0).build()
        val cancelled = client.readDriveState()
        assertEquals(NavigationDestination.None, cancelled.destination)
        assertNull(cancelled.distanceKm)
        assertEquals(listOf(11, 12, 13, 14, 15, 16), vehicle.counters)
    }

    @Test fun `odometer converts hundredths of a mile to kilometers and stays optional`() {
        assertEquals(16093.44, DriveSnapshot.from(DriveState.newBuilder().setOdometerInHundredthsOfAMile(1_000_000).build()).odometerKm!!, 0.0001)
        assertNull(DriveSnapshot.from(DriveState.getDefaultInstance()).odometerKm)
        assertNull(DriveSnapshot.from(DriveState.newBuilder().setOdometerInHundredthsOfAMile(-1).build()).odometerKm)
    }

    @Test fun `charge state maps plug status and energy alongside the battery figures`() {
        val void = Void.getDefaultInstance()
        val unplugged = ChargeState.newBuilder().setChargingState(ChargeState.ChargingState.newBuilder().setDisconnected(void))
            .setChargeEnergyAdded(31.4f).setBatteryLevel(80).setBatteryRange(200f).build()
        val parsed = ChargeSnapshot.from(unplugged)
        assertEquals(ChargingStatus.DISCONNECTED, parsed.status)
        assertEquals(31.4, parsed.energyAddedKwh!!, 0.001)
        assertEquals(80, parsed.batteryLevel)
        assertEquals(BatterySnapshot.from(unplugged), parsed.battery)
        assertEquals(ChargingStatus.CHARGING, ChargeSnapshot.from(ChargeState.newBuilder()
            .setChargingState(ChargeState.ChargingState.newBuilder().setCharging(void)).build()).status)
        assertEquals(ChargingStatus.COMPLETE, ChargeSnapshot.from(ChargeState.newBuilder()
            .setChargingState(ChargeState.ChargingState.newBuilder().setComplete(void)).build()).status)
        assertEquals(ChargeSnapshot(ChargingStatus.UNKNOWN, null), ChargeSnapshot.from(ChargeState.getDefaultInstance()))
        assertNull(ChargeSnapshot.from(ChargeState.newBuilder().setChargeEnergyAdded(-1f).build()).energyAddedKwh)
    }

    @Test fun `bad handshake signature or another VIN is rejected`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys).apply { corruptHandshake = true }
        assertFailsWith<IllegalArgumentException> { TeslaClient(VIN, keys, vehicle).authenticate() }
        assertFailsWith<IllegalArgumentException> { TeslaClient("5YJYGDEE1NF123456", keys, FakeVehicle(keys)).authenticate() }
    }

    @Test fun `plaintext and tampered encrypted data cannot become a destination`() = runTest {
        for (plaintext in listOf(true, false)) {
            val keys = TeslaKeys.generate()
            val vehicle = FakeVehicle(keys)
            val client = TeslaClient(VIN, keys, vehicle)
            client.authenticate()
            vehicle.plaintextResponse = plaintext
            vehicle.corruptCiphertext = !plaintext
            assertFails { client.readDriveState() }
        }
    }

    @Test fun `stale response from previous request is ignored`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        vehicle.nextState = DriveState.newBuilder().setActiveRouteDestination("이전 목적지").build()
        client.readDriveState()
        vehicle.replayPrevious = true
        vehicle.nextState = DriveState.newBuilder().setActiveRouteDestination("새 목적지").build()
        assertEquals("새 목적지", (client.readDriveState().destination as NavigationDestination.Active).name)
    }

    @Test fun `battery uses rated range in km and ignores estimated range and usable level`() {
        val state = ChargeState.newBuilder().setBatteryLevel(78).setUsableBatteryLevel(76)
            .setBatteryRange(218.73f).setEstBatteryRange(190f).setIdealBatteryRange(240f).build()
        val battery = BatterySnapshot.from(state)
        assertEquals(78, battery.levelPercent)
        assertEquals(218.73 * 1.609344, battery.rangeKm!!, 0.001)
        assertEquals(BatterySnapshot(0, 0.0), BatterySnapshot.from(ChargeState.newBuilder().setBatteryLevel(0).setBatteryRange(0f).build()))
    }

    @Test fun `missing or abnormal battery fields become null without hiding the other field`() {
        assertTrue(BatterySnapshot.from(ChargeState.getDefaultInstance()).empty)
        // Only estimated values present: never substitute them for the rated figures.
        assertTrue(BatterySnapshot.from(ChargeState.newBuilder().setUsableBatteryLevel(70).setEstBatteryRange(200f).build()).empty)
        for (level in listOf(-1, 101, Int.MAX_VALUE)) {
            val battery = BatterySnapshot.from(ChargeState.newBuilder().setBatteryLevel(level).setBatteryRange(100f).build())
            assertNull(battery.levelPercent)
            assertEquals(160.9344, battery.rangeKm!!, 0.0001)
        }
        for (miles in listOf(-0.5f, Float.NaN, Float.POSITIVE_INFINITY, 1000.5f)) {
            val battery = BatterySnapshot.from(ChargeState.newBuilder().setBatteryLevel(50).setBatteryRange(miles).build())
            assertEquals(50, battery.levelPercent)
            assertNull(battery.rangeKm)
        }
    }

    @Test fun `encrypted charge read separates data, absent charge state and vehicle refusal`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        vehicle.chargeState = ChargeState.newBuilder().setBatteryLevel(64).setBatteryRange(150f).build()
        assertEquals(BatterySnapshot(64, 150 * 1.609344), client.readChargeState()!!.battery)
        vehicle.chargeState = null
        assertNull(client.readChargeState())
        vehicle.chargeRefusal = "권한 없음"
        assertEquals("차량 조회 실패: 권한 없음", assertFailsWith<IllegalStateException> { client.readChargeState() }.message)
        vehicle.nextState = DriveState.newBuilder().setActiveRouteDestination("서울역").build()
        assertEquals("서울역", (client.readDriveState().destination as NavigationDestination.Active).name)
        assertEquals(listOf("charge", "charge", "charge", "drive", "location"), vehicle.queries)
    }

    @Test fun `drive read reports vehicle error reason even when drive state is attached`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        vehicle.nextState = DriveState.newBuilder().setActiveRouteDestination("이전 목적지").build()
        vehicle.driveRefusal = "차량 절전 중"
        assertEquals("차량 조회 실패: 차량 절전 중", assertFailsWith<IllegalStateException> { client.readDriveState() }.message)
        vehicle.driveRefusal = ""
        assertEquals("차량 조회 실패: 상세 사유 없음", assertFailsWith<IllegalStateException> { client.readDriveState() }.message)
        vehicle.driveRefusal = null
        assertEquals("이전 목적지", (client.readDriveState().destination as NavigationDestination.Active).name)
    }

    @Test fun `unauthenticated VCSEC status tells asleep from awake without request uuid`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys).apply { sleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP }
        val client = TeslaClient(VIN, keys, vehicle)
        assertEquals(VehicleSleep.ASLEEP, client.readVehicleSleep())
        vehicle.sleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE
        assertEquals(VehicleSleep.AWAKE, client.readVehicleSleep())
        vehicle.sleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_UNKNOWN
        assertEquals(VehicleSleep.UNKNOWN, client.readVehicleSleep())
        // The status check needs no session and leaves the authenticated path untouched.
        client.authenticate()
        vehicle.nextState = DriveState.newBuilder().setActiveRouteDestination("서울역").build()
        assertEquals("서울역", (client.readDriveState().destination as NavigationDestination.Active).name)
        assertEquals(3, vehicle.statusRequests)
    }

    @Test fun `silent VCSEC or a reply for another connection gives no sleep answer`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys).apply { sleepStatus = null }
        val client = TeslaClient(VIN, keys, vehicle)
        assertNull(client.readVehicleSleep(timeoutMs = 1000))
        vehicle.sleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP
        vehicle.vcsecRoutingAddress = ByteArray(16) { 9 }.bytes()
        assertNull(client.readVehicleSleep(timeoutMs = 1000))
        // An unanswered status check does not block the handshake that follows.
        client.authenticate()
    }

    @Test fun `vehicle location is requested separately to fit BLE response size and parsed`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        vehicle.nextState = DriveState.newBuilder().setSpeedFloat(30f).build()
        vehicle.nextLocation = LocationState.newBuilder().setLatitude(37.5f).setLongitude(127.0f)
            .setHeading(90).setGpsAsOf(1_760_000_000L).build()
        val snapshot = client.readDriveState()
        assertEquals(GeoPoint(37.5, 127.0), snapshot.location!!.point)
        assertEquals(90, snapshot.location!!.headingDegrees)
        assertEquals(1_760_000_000L, snapshot.location!!.gpsAsOf)
        assertEquals(48.28032, snapshot.speedKph!!, 0.0001)
        assertNull(client.locationRejection)
        assertEquals(listOf("drive", "location"), vehicle.queries)
    }

    @Test fun `refused location keeps drive state and stops asking on that connection`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys).apply { refuseLocation = true }
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        vehicle.nextState = DriveState.newBuilder().setActiveRouteDestination("서울역").build()
        val first = client.readDriveState()
        assertEquals("서울역", (first.destination as NavigationDestination.Active).name)
        assertNull(first.location)
        assertEquals("차량 조회 실패: unauthorized", client.locationRejection)
        client.readDriveState()
        assertEquals(listOf("drive", "location", "drive"), vehicle.queries)
    }

    @Test fun `busy vehicle fault is not mistaken for a location refusal`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        vehicle.faults += 1
        assertEquals(1, assertFailsWith<VehicleFault> { client.readDriveState() }.code)
        assertNull(client.locationRejection)
        vehicle.nextLocation = LocationState.newBuilder().setLatitude(37.5f).setLongitude(127.0f).build()
        assertEquals(GeoPoint(37.5, 127.0), client.readDriveState().location!!.point)
        assertEquals(listOf("drive", "drive", "location"), vehicle.queries)
    }

    @Test fun `drive refusal is not recorded as a location refusal`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys).apply { driveRefusal = "차량 절전 중" }
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        assertEquals("차량 조회 실패: 차량 절전 중", assertFailsWith<IllegalStateException> { client.readDriveState() }.message)
        assertNull(client.locationRejection)
        assertEquals(listOf("drive"), vehicle.queries)
    }

    @Test fun `temporary optional location faults and timeout do not lose drive or disable location`() = runTest {
        for (fault in listOf(1, 6, 25, 0)) {
            val keys = TeslaKeys.generate()
            val vehicle = FakeVehicle(keys).apply { locationFaults += fault }
            val client = TeslaClient(VIN, keys, vehicle)
            client.authenticate()
            vehicle.nextState = DriveState.newBuilder().setActiveRouteDestination("서울역").setSpeedFloat(30f).build()
            assertEquals("서울역", (client.readDriveState().destination as NavigationDestination.Active).name)
            assertNull(client.locationRejection)
            assertEquals(1, vehicle.handshakes)
            vehicle.nextLocation = LocationState.newBuilder().setLatitude(37.5f).setLongitude(127f).build()
            assertEquals(GeoPoint(37.5, 127.0), client.readDriveState().location!!.point)
            assertEquals(listOf("drive", "location", "drive", "location"), vehicle.queries)
        }
    }

    @Test fun `native WGS estimated location is preferred and carries freshness metadata`() {
        val state = LocationState.newBuilder().setLatitude(37.0f).setLongitude(126f)
            .setNativeLatitude(37.5f).setNativeLongitude(127f)
            .setNativeType(LocationState.GPSCoordinateType.newBuilder().setWGS(Void.getDefaultInstance()))
            .setEstimatedGpsValid(true).setTimestamp(com.google.protobuf.Timestamp.newBuilder().setSeconds(1_760_000_000)).build()
        val location = VehicleLocation.from(state)
        assertEquals(GeoPoint(37.5, 127.0), location.point)
        assertEquals(true, location.estimatedValid)
        assertEquals(1_760_000_000_000L, location.timestampMillis)
        val gcj = state.toBuilder().setNativeType(LocationState.GPSCoordinateType.newBuilder().setGCJ(Void.getDefaultInstance())).build()
        assertEquals(GeoPoint(37.0, 126.0), VehicleLocation.from(gcj).point)
    }

    @Test fun `temporary location action refusal is retried without another authentication`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys).apply { locationRefusal = "temporarily unavailable" }
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        assertNull(client.readDriveState().location)
        assertNull(client.locationRejection)
        vehicle.locationRefusal = null
        vehicle.nextLocation = LocationState.newBuilder().setLatitude(37.5f).setLongitude(127f).build()
        assertNotNull(client.readDriveState().location)
        assertEquals(1, vehicle.handshakes)
    }

    @Test fun `missing or zero vehicle coordinates are not a usable fix`() {
        assertNull(VehicleLocation.from(LocationState.newBuilder().setLatitude(0f).setLongitude(0f).build()).point)
        assertNull(VehicleLocation.from(LocationState.newBuilder().setLatitude(37.5f).build()).point)
        assertNull(DriveSnapshot.from(DriveState.getDefaultInstance()).location)
    }

    @Test fun `charge read session fault leaves session renewal to the drive read`() = runTest {
        val keys = TeslaKeys.generate()
        val vehicle = FakeVehicle(keys)
        val client = TeslaClient(VIN, keys, vehicle)
        client.authenticate()
        vehicle.faults += 6
        assertEquals(6, assertFailsWith<VehicleFault> { client.readChargeState() }.code)
        assertEquals(1, vehicle.handshakes)
        vehicle.faults += 6
        client.readDriveState()
        assertEquals(2, vehicle.handshakes)
    }
}

private class FakeVehicle(private val phone: KeyPair) : VehicleTransport {
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val vehicle = TeslaKeys.generate()
    private val key = TeslaKeys.sharedKey(vehicle.private, TeslaKeys.publicBytes(phone.public))
    private val epoch = ByteArray(16) { (it + 1).toByte() }
    var nextState: DriveState = DriveState.getDefaultInstance()
    var chargeState: ChargeState? = null
    var chargeRefusal: String? = null
    var driveRefusal: String? = null
    var nextLocation: LocationState? = null
    var refuseLocation = false
    var locationRefusal: String? = null
    val faults = ArrayDeque<Int>()
    /** Zero simulates a silent optional query. */
    val locationFaults = ArrayDeque<Int>()
    /** VCSEC's answer to the unauthenticated status request; null means VCSEC stays silent. */
    var sleepStatus: VehicleSleepStatus_E? = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE
    var vcsecRoutingAddress: com.google.protobuf.ByteString? = null
    var statusRequests = 0
    val queries = mutableListOf<String>()
    var handshakes = 0
    var corruptHandshake = false
    var plaintextResponse = false
    var corruptCiphertext = false
    var replayPrevious = false
    var rawEnrollment = byteArrayOf()
    var previousResponse: ByteArray? = null
    var beforeHandshake: ((RoutableMessage) -> ByteArray)? = null
    val counters = mutableListOf<Int>()

    override suspend fun send(message: ByteArray) {
        val request = RoutableMessage.parseFrom(message)
        if (!request.hasToDestination()) { rawEnrollment = message; return }
        if (request.toDestination.domain == Domain.DOMAIN_VEHICLE_SECURITY) {
            // Unauthenticated status request, answered like VCSEC: no signature and no request_uuid.
            check(!request.hasSignatureData())
            check(UnsignedMessage.parseFrom(request.protobufMessageAsBytes).informationRequest.informationRequestType ==
                InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS)
            statusRequests++
            val sleep = sleepStatus ?: return
            incoming.send(RoutableMessage.newBuilder()
                .setFromDestination(Destination.newBuilder().setDomain(Domain.DOMAIN_VEHICLE_SECURITY))
                .setToDestination(Destination.newBuilder().setRoutingAddress(vcsecRoutingAddress ?: request.fromDestination.routingAddress))
                .setProtobufMessageAsBytes(FromVCSECMessage.newBuilder()
                    .setVehicleStatus(VehicleStatus.newBuilder().setVehicleSleepStatus(sleep)).build().toByteString())
                .build().toByteArray())
            return
        }
        val reply = RoutableMessage.newBuilder()
            .setFromDestination(Destination.newBuilder().setDomain(Domain.DOMAIN_INFOTAINMENT))
            .setToDestination(request.fromDestination).setRequestUuid(request.uuid)
        if (request.hasSessionInfoRequest()) {
            handshakes++
            beforeHandshake?.let { incoming.send(it(request)) }
            val info = SessionInfo.newBuilder().setCounter(10).setClockTime(200)
                .setPublicKey(TeslaKeys.publicBytes(vehicle.public).bytes()).setEpoch(epoch.bytes()).build()
            val subkey = TeslaSession.hmac(key, "session info".toByteArray())
            val tag = TeslaSession.hmac(subkey, metadata(0 to byteArrayOf(6), 2 to VIN.toByteArray(),
                6 to request.uuid.toByteArray()) + info.toByteArray())
            if (corruptHandshake) tag[0] = (tag[0].toInt() xor 1).toByte()
            reply.setSessionInfo(info.toByteString()).setSignatureData(SignatureData.newBuilder()
                .setSessionInfoTag(HMAC_Signature_Data.newBuilder().setTag(tag.bytes())))
        } else {
            val signature = request.signatureData.getAESGCMPersonalizedData()
            counters += signature.counter
            // Verify the request at the simulated vehicle, rather than accepting arbitrary ciphertext.
            val requestAad = MessageDigest.getInstance("SHA-256").digest(metadata(
                0 to byteArrayOf(5), 1 to byteArrayOf(3), 2 to VIN.toByteArray(), 3 to epoch,
                4 to uint32(Integer.toUnsignedLong(signature.expiresAt)),
                5 to uint32(Integer.toUnsignedLong(signature.counter)), 7 to uint32(2)))
            val actionBytes = TeslaSession.crypt(Cipher.DECRYPT_MODE, key, signature.nonce.toByteArray(),
                request.protobufMessageAsBytes.toByteArray() + signature.tag.toByteArray(), requestAad)
            val query = com.tesla.generated.carserver.server.Action.parseFrom(actionBytes).vehicleAction.getVehicleData
            check(listOf(query.hasGetDriveState(), query.hasGetChargeState(), query.hasGetLocationState()).count { it } == 1)
            queries += when { query.hasGetDriveState() -> "drive"; query.hasGetLocationState() -> "location"; else -> "charge" }
            (if (query.hasGetLocationState()) locationFaults.removeFirstOrNull() else faults.removeFirstOrNull())?.let {
                if (it == 0) return
                incoming.send(reply.setSignedMessageStatus(MessageStatus.newBuilder().setSignedMessageFaultValue(it)).build().toByteArray())
                return
            }
            val refusal = when {
                query.hasGetDriveState() && driveRefusal != null -> driveRefusal
                refuseLocation && query.hasGetLocationState() -> "unauthorized"
                query.hasGetLocationState() && locationRefusal != null -> locationRefusal
                query.hasGetDriveState() -> null
                query.hasGetLocationState() -> null
                else -> chargeRefusal
            }
            val response = Response.newBuilder().apply {
                refusal?.let {
                    setActionStatus(ActionStatus.newBuilder().setResult(OperationStatus_E.OPERATIONSTATUS_ERROR)
                        .setResultReason(ResultReason.newBuilder().setPlainText(it)))
                }
                // A drive refusal still carries drive state, so the status alone must reject it.
                if (query.hasGetDriveState()) setVehicleData(VehicleData.newBuilder().setDriveState(nextState))
                else if (query.hasGetLocationState()) setVehicleData(VehicleData.newBuilder().apply {
                    if (refusal == null) this@FakeVehicle.nextLocation?.let { setLocationState(it) }
                })
                else if (refusal == null) setVehicleData(VehicleData.newBuilder().apply { this@FakeVehicle.chargeState?.let { setChargeState(it) } })
            }.build().toByteArray()
            if (plaintextResponse) reply.setProtobufMessageAsBytes(response.bytes())
            else {
                val aad = MessageDigest.getInstance("SHA-256").digest(metadata(
                    0 to byteArrayOf(9), 1 to byteArrayOf(3), 2 to VIN.toByteArray(),
                    5 to uint32(1), 7 to uint32(0), 8 to (byteArrayOf(5) + signature.tag.toByteArray()), 9 to uint32(0)))
                val nonce = ByteArray(12) { (it + 2).toByte() }
                val sealed = TeslaSession.crypt(Cipher.ENCRYPT_MODE, key, nonce, response, aad)
                if (corruptCiphertext) sealed[0] = (sealed[0].toInt() xor 1).toByte()
                reply.setProtobufMessageAsBytes(sealed.copyOf(sealed.size - 16).bytes())
                    .setSignatureData(SignatureData.newBuilder().setAESGCMResponseData(AES_GCM_Response_Signature_Data.newBuilder()
                        .setNonce(nonce.bytes()).setCounter(1).setTag(sealed.takeLast(16).toByteArray().bytes())))
            }
            if (replayPrevious) previousResponse?.let { incoming.send(it) }
        }
        val bytes = reply.build().toByteArray()
        incoming.send(bytes)
        if (!request.hasSessionInfoRequest()) previousResponse = bytes
    }
    override fun close() { incoming.close() }
}
