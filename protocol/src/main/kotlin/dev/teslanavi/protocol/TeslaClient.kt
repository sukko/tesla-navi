package dev.teslanavi.protocol

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.GetChargeState
import com.tesla.generated.carserver.server.GetDriveState
import com.tesla.generated.carserver.server.GetLocationState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import com.tesla.generated.vcsec.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.security.KeyPair

interface VehicleTransport {
    val incoming: ReceiveChannel<ByteArray>
    suspend fun send(message: ByteArray)
    fun close()
    fun diagnostics(): String = ""
}

class VehicleFault(val code: Int) : Exception(when (code) {
    1, 2, 11 -> "차량이 준비 중입니다. 잠시 후 다시 연결해 주세요. (코드 $code)"
    3 -> "차량에 앱 키가 없습니다. 키 등록을 먼저 진행해 주세요."
    4 -> "앱 키가 비활성화되어 있습니다. 차량의 키 목록을 확인해 주세요."
    7 -> "차량이 데이터 조회 권한을 허용하지 않았습니다."
    21 -> "차량 설정에서 모바일 액세스를 켜 주세요."
    25 -> "차량 응답 크기가 BLE 전송 한도를 넘었습니다. (코드 25)"
    else -> "차량 인증 또는 조회 오류 (코드 $code). 다시 연결해 주세요."
})

/** The vehicle answered the request but refused it in the action status. */
private class VehicleDataRejected(message: String) : IllegalStateException(message)

private val SESSION_FAULTS = setOf(5, 6, 15, 17, 26)

/** One serialized request at a time; no unrelated or unauthenticated response becomes vehicle data. */
class TeslaClient(private val vin: String, private val keys: KeyPair, private val transport: VehicleTransport, private val diagnostic: (String) -> Unit = {}) {
    private val mutex = Mutex()
    private var session: TeslaSession? = null
    private var enrollmentPending = false
    private val address = randomBytes(16).bytes()

    suspend fun requestKeyEnrollment() = mutex.withLock {
        val payload = UnsignedMessage.newBuilder().setWhitelistOperation(
            WhitelistOperation.newBuilder()
                .setAddKeyToWhitelistAndAddPermissions(PermissionChange.newBuilder()
                    .setKey(PublicKey.newBuilder().setPublicKeyRaw(TeslaKeys.publicBytes(keys.public).bytes()))
                    .setKeyRole(Role.ROLE_VEHICLE_MONITOR))
                .setMetadataForKey(KeyMetadata.newBuilder().setKeyFormFactor(KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE))
        ).build()
        val envelope = ToVCSECMessage.newBuilder().setSignedMessage(SignedMessage.newBuilder()
            .setSignatureType(SignatureType.SIGNATURE_TYPE_PRESENT_KEY)
            .setProtobufMessageAsBytes(payload.toByteString())).build()
        // Raw VCSEC envelope is intentional: this step is approved with the physical key card.
        transport.send(envelope.toByteArray())
        enrollmentPending = true
    }

    suspend fun authenticate() = mutex.withLock { handshake() }

    /**
     * Unauthenticated VCSEC status, the same request as Tesla's body-controller-state. VCSEC answers it
     * while infotainment sleeps, so it tells whether an infotainment handshake can be answered at all.
     * Null when VCSEC does not answer in time or reports a fault; callers then proceed as before.
     */
    suspend fun readVehicleSleep(timeoutMs: Long = 3000): VehicleSleep? = mutex.withLock {
        val payload = UnsignedMessage.newBuilder().setInformationRequest(InformationRequest.newBuilder()
            .setInformationRequestType(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS)).build()
        val request = baseMessage(Domain.DOMAIN_VEHICLE_SECURITY).setProtobufMessageAsBytes(payload.toByteString()).build()
        try {
            transport.send(request.toByteArray())
            val reply = awaitReply(request, timeoutMs, Domain.DOMAIN_VEHICLE_SECURITY) { !it.protobufMessageAsBytes.isEmpty }
            val status = FromVCSECMessage.parseFrom(reply.protobufMessageAsBytes).vehicleStatus
            when (status.vehicleSleepStatus) {
                VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP -> VehicleSleep.ASLEEP
                VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE -> VehicleSleep.AWAKE
                else -> VehicleSleep.UNKNOWN
            }
        } catch (error: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
        catch (_: VehicleFault) { null }
        catch (_: com.google.protobuf.InvalidProtocolBufferException) { null }
    }

    private suspend fun handshake() {
        session = null
        val request = baseMessage().setSessionInfoRequest(SessionInfoRequest.newBuilder()
            .setPublicKey(TeslaKeys.publicBytes(keys.public).bytes())).build()
        val startedAt = System.nanoTime()
        transport.send(request.toByteArray())
        val reply = awaitReply(request, 4000) { !it.sessionInfo.isEmpty }
        require((System.nanoTime() - startedAt) < 4_000_000_000L) { "차량 인증 응답이 지연되었습니다. 다시 연결해 주세요." }
        require(reply.signatureData.hasSessionInfoTag()) { "차량 인증 서명이 누락되었습니다." }
        session = TeslaSession.authenticate(vin, keys, request.uuid.toByteArray(),
            reply.sessionInfo.toByteArray(), reply.signatureData.sessionInfoTag.tag.toByteArray())
        enrollmentPending = false
    }

    /** Why the vehicle refused LocationState on this connection; later reads then ask for drive state only. */
    @Volatile var locationRejection: String? = null
        private set

    suspend fun readDriveState(): DriveSnapshot = mutex.withLock {
        // Combining drive and location easily exceeds the vehicle's BLE response-size ceiling.
        val response = vehicleData(GetVehicleData.newBuilder().setGetDriveState(GetDriveState.getDefaultInstance()), 8000, renewSession = true)
        check(response.hasVehicleData() && response.vehicleData.hasDriveState()) { "차량에서 주행 상태를 제공하지 않았습니다." }
        val location = if (locationRejection != null) null else try {
            val reply = vehicleData(GetVehicleData.newBuilder().setGetLocationState(GetLocationState.getDefaultInstance()), 2000, renewSession = false)
            if (reply.hasVehicleData() && reply.vehicleData.hasLocationState()) reply.vehicleData.locationState else null
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive(); null
        } catch (error: VehicleFault) {
            // Size, busy and session faults are temporary. The next drive poll retries location.
            if (error.code == 7) locationRejection = error.message
            diagnostic("차량 위치 보조 조회 실패 · 코드 ${error.code} · 주행 조회 유지")
            null
        } catch (error: VehicleDataRejected) {
            val reason = error.message.orEmpty().lowercase(java.util.Locale.ROOT)
            if (listOf("unauthorized", "permission", "not supported", "권한", "지원하지").any { it in reason })
                locationRejection = error.message
            diagnostic("차량 위치 조회 거절 · 주행 조회 유지 · ${error.message}")
            null
        }
        DriveSnapshot.from(response.vehicleData.driveState, location)
    }

    /**
     * Null when the vehicle answers without charge_state. Never renews the session: a session
     * fault is left to the next drive read so this optional read cannot reset the connection.
     */
    suspend fun readChargeState(timeoutMs: Long = 4000): ChargeSnapshot? = mutex.withLock {
        val response = vehicleData(GetVehicleData.newBuilder().setGetChargeState(GetChargeState.getDefaultInstance()), timeoutMs, renewSession = false)
        if (response.hasVehicleData() && response.vehicleData.hasChargeState()) ChargeSnapshot.from(response.vehicleData.chargeState) else null
    }

    private suspend fun vehicleData(query: GetVehicleData.Builder, timeoutMs: Long, renewSession: Boolean): Response {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val activeSession = session ?: error("차량 인증을 먼저 진행해 주세요.")
                val action = Action.newBuilder().setVehicleAction(VehicleAction.newBuilder().setGetVehicleData(query)).build()
                val request = activeSession.encrypt(baseMessage().setFlags(2)
                    .setProtobufMessageAsBytes(action.toByteString()).build())
                transport.send(request.message.toByteArray())
                val reply = awaitReply(request.message, timeoutMs) { !it.protobufMessageAsBytes.isEmpty }
                val response = Response.parseFrom(activeSession.decrypt(reply, request.responseId))
                if (response.actionStatus.result == OperationStatus_E.OPERATIONSTATUS_ERROR) throw VehicleDataRejected(
                    "차량 조회 실패: ${response.actionStatus.resultReason.plainText.ifBlank { "상세 사유 없음" }}")
                return response
            } catch (error: VehicleFault) {
                if (renewSession && attempt == 0 && error.code in SESSION_FAULTS) handshake() else throw error
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("차량 상태 조회 실패")
    }

    private fun baseMessage(domain: Domain = Domain.DOMAIN_INFOTAINMENT) = RoutableMessage.newBuilder()
        .setToDestination(Destination.newBuilder().setDomain(domain))
        .setFromDestination(Destination.newBuilder().setRoutingAddress(address))
        .setUuid(randomBytes(16).bytes())

    private suspend fun awaitReply(request: RoutableMessage, timeoutMs: Long, domain: Domain = Domain.DOMAIN_INFOTAINMENT,
        accept: (RoutableMessage) -> Boolean): RoutableMessage {
        var received = 0
        var malformed = 0
        var unrelated = 0
        var empty = 0
        try { return withTimeout(timeoutMs) {
        while (true) {
            val bytes = transport.incoming.receive()
            received++
            val response = try { RoutableMessage.parseFrom(bytes) } catch (_: Exception) { malformed++; continue }
            if (enrollmentPending) {
                // Legacy enrollment is sent without a routing envelope. Some replies are raw
                // VCSEC; routed replies must originate from VCSEC and target this connection.
                val enrollmentPayload = when {
                    !response.hasFromDestination() && !response.hasToDestination() -> bytes
                    response.fromDestination.domain == Domain.DOMAIN_VEHICLE_SECURITY &&
                        response.toDestination.routingAddress == address -> response.protobufMessageAsBytes.toByteArray()
                    else -> null
                }
                enrollmentPayload?.let { enrollmentFailure(it)?.let { error -> throw error } }
            }
            if (response.toDestination.routingAddress != address || response.fromDestination.domain != domain) { unrelated++; continue }
            val sameRequest = response.requestUuid == request.uuid ||
                (domain == Domain.DOMAIN_VEHICLE_SECURITY && response.requestUuid.isEmpty)
            if (!sameRequest) { unrelated++; continue }
            val fault = response.signedMessageStatus.signedMessageFaultValue
            if (fault != 0) throw VehicleFault(fault)
            if (accept(response)) return@withTimeout response
            empty++
        }
        @Suppress("UNREACHABLE_CODE") error("응답 없음")
        } } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val stage = if (request.hasSessionInfoRequest()) "차량 인증" else if (domain == Domain.DOMAIN_VEHICLE_SECURITY) "차량 수면 상태" else "차량 데이터"
            diagnostic("$stage 응답 시간 초과 · 수신=$received, 형식오류=$malformed, 다른요청=$unrelated, 내용없음=$empty · ${transport.diagnostics()}")
            throw error
        }
    }

    fun close() { session = null; transport.close() }
}
