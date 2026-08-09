package com.relay.websocket.handler

import com.relay.common.dto.AcceptCallRequest
import com.relay.common.dto.HangupCallRequest
import com.relay.common.dto.IceCandidateRequest
import com.relay.common.dto.InviteCallRequest
import com.relay.common.dto.RejectCallRequest
import com.relay.common.event.KafkaTopics
import com.relay.common.event.SendMessageCommand
import com.relay.common.model.UserPrincipal
import com.relay.websocket.input.handler.InboundFrameRouter
import com.relay.websocket.output.event.MessageEventProducer
import com.relay.websocket.output.http.CallClient
import com.relay.websocket.output.http.CallSignalResult
import com.relay.websocket.protocol.ErrorCodes
import com.relay.websocket.protocol.FrameCodec
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class InboundFrameRouterTest {

    private val jsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate =
        mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>

    private val callClient = RecordingCallClient()

    // A real producer over a mocked template: the test pins the whole gateway-side contract —
    // topic, partition key, and serialized command — not just that "something was published".
    private val router = InboundFrameRouter(
        FrameCodec(),
        MessageEventProducer(kafkaTemplate, jsonMapper),
        callClient
    )

    private fun session(userId: String = "alice") =
        RelaySession("s-1", UserPrincipal(userId, null, emptySet()), 16)

    /** Stands in for the writer thread; the frame is already queued, so this does not park. */
    private fun RelaySession.nextFrame(): OutboundFrame =
        assertIs<RelaySession.Outbound.Frame>(awaitOutbound()).frame

    private fun queueSucceeds() {
        `when`(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null as SendResult<String, String>?))
    }

    @Test
    fun `answers a ping with a pong echoing the frame id`() {
        val session = session()

        router.route(session, """{"v":1,"type":"ping","id":"p-7"}""")

        assertEquals("p-7", assertIs<OutboundFrame.Pong>(session.nextFrame()).refId)
    }

    @Test
    fun `queues a send with the sender taken from the session, not the frame`() {
        queueSucceeds()
        val session = session(userId = "alice")

        // The payload even tries to claim a different sender; it must be ignored.
        router.route(
            session,
            """{"v":1,"type":"message.send","id":"c-1",
                "payload":{"dialog_id":"d-1","text":"hello","sender_id":"mallory"}}"""
        )

        val value = ArgumentCaptor.forClass(String::class.java)
        verify(kafkaTemplate).send(eq(KafkaTopics.MESSAGES_INCOMING), eq("d-1"), value.capture())
        val command = jsonMapper.readValue(value.value, SendMessageCommand::class.java)
        assertEquals("alice", command.senderId, "a client must not be able to send as someone else")
        assertEquals("c-1", command.clientMessageId)
        assertEquals("s-1", command.senderSessionId, "the ack must find its way back to this device")
    }

    @Test
    fun `send produces no immediate frame - the ack arrives via the delivery event`() {
        queueSucceeds()
        val session = session()

        router.route(
            session,
            """{"v":1,"type":"message.send","id":"c-1","payload":{"dialog_id":"d-1","text":"hello"}}"""
        )

        // Completing puts the terminal marker at the head, so it arriving first proves nothing
        // was queued ahead of it.
        session.complete()
        assertEquals(RelaySession.Outbound.Completed, session.awaitOutbound())
    }

    @Test
    fun `a failed queue hand-off produces SEND_FAILED with the frame id`() {
        `when`(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("broker down")))
        val session = session()

        router.route(
            session,
            """{"v":1,"type":"message.send","id":"c-1","payload":{"dialog_id":"d-1","text":"hello"}}"""
        )

        val error = assertIs<OutboundFrame.Error>(session.nextFrame())
        assertEquals(ErrorCodes.SEND_FAILED, error.code)
        assertEquals("c-1", error.refId, "the client retries exactly this send over REST")
    }

    @Test
    fun `an unparseable frame gets BAD_FRAME and nothing reaches the queue`() {
        val session = session()

        router.route(session, "not json at all")

        assertEquals(ErrorCodes.BAD_FRAME, assertIs<OutboundFrame.Error>(session.nextFrame()).code)
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    fun `a wrong protocol version is refused without touching the queue`() {
        val session = session()

        router.route(session, """{"v":99,"type":"message.send","id":"c-1","payload":{"dialog_id":"d","text":"x"}}""")

        assertEquals(
            ErrorCodes.UNSUPPORTED_VERSION,
            assertIs<OutboundFrame.Error>(session.nextFrame()).code
        )
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString())
    }

    // ---- call signaling ----

    @Test
    fun `forwards an invite with the caller and session taken from the socket`() {
        val session = session(userId = "alice")

        // The payload tries to name a different caller; it must be ignored.
        router.route(
            session,
            """{"v":1,"type":"call.invite","id":"f-1",
                "payload":{"call_id":"c-1","callee_id":"bob","media":"video","sdp":"v=0 offer",
                           "caller_id":"mallory","dialog_id":"d-1"}}"""
        )

        val request = assertIs<InviteCallRequest>(callClient.forwarded.single().request)
        assertEquals("alice", request.callerId, "a client must not be able to call as someone else")
        assertEquals("s-1", request.sessionId, "the answer has to find this device")
        assertEquals("c-1", request.callId)
        assertEquals("bob", request.calleeId)
        assertEquals("video", request.media)
        assertEquals("v=0 offer", request.sdp)
        assertEquals("d-1", request.dialogId)
    }

    @Test
    fun `an accepted signal produces no frame - what participants see arrives off the topic`() {
        val session = session()

        router.route(
            session,
            """{"v":1,"type":"call.accept","id":"f-2","payload":{"call_id":"c-1","sdp":"v=0 answer"}}"""
        )

        val forwarded = callClient.forwarded.single()
        assertEquals("accept c-1", forwarded.description)
        assertEquals("alice", assertIs<AcceptCallRequest>(forwarded.request).userId)

        // Completing puts the terminal marker at the head, so it arriving first proves nothing
        // was queued ahead of it.
        session.complete()
        assertEquals(RelaySession.Outbound.Completed, session.awaitOutbound())
    }

    @Test
    fun `a rejected signal becomes an error frame carrying the downstream code`() {
        callClient.answer = CallSignalResult.Rejected("USER_BUSY", "bob is already in a call")
        val session = session()

        router.route(
            session,
            """{"v":1,"type":"call.invite","id":"f-9",
                "payload":{"call_id":"c-1","callee_id":"bob","media":"audio","sdp":"v=0"}}"""
        )

        val error = assertIs<OutboundFrame.Error>(session.nextFrame())
        assertEquals("USER_BUSY", error.code)
        assertEquals("bob is already in a call", error.message)
        assertEquals("f-9", error.refId, "the client fails this specific attempt, not the whole call UI")
    }

    @Test
    fun `an unreachable call service becomes CALL_SIGNAL_FAILED`() {
        callClient.answer =
            CallSignalResult.Rejected(ErrorCodes.CALL_SIGNAL_FAILED, "Call service is unavailable")
        val session = session()

        router.route(
            session,
            """{"v":1,"type":"call.ice","id":"f-3",
                "payload":{"call_id":"c-1","candidate":{"candidate":"candidate:1"}}}"""
        )

        assertEquals(
            ErrorCodes.CALL_SIGNAL_FAILED,
            assertIs<OutboundFrame.Error>(session.nextFrame()).code
        )
    }

    @Test
    fun `forwards a candidate untouched`() {
        val session = session()

        router.route(
            session,
            """{"v":1,"type":"call.ice","id":"f-3",
                "payload":{"call_id":"c-1","candidate":{"candidate":"candidate:1 1 udp","sdpMid":"0"}}}"""
        )

        val forwarded = callClient.forwarded.single()
        assertEquals("ice c-1", forwarded.description)
        val request = assertIs<IceCandidateRequest>(forwarded.request)
        assertEquals("candidate:1 1 udp", request.candidate["candidate"])
        assertEquals("0", request.candidate["sdpMid"])
    }

    @Test
    fun `forwards reject and hangup with their optional reason`() {
        val session = session()

        router.route(
            session,
            """{"v":1,"type":"call.reject","id":"f-4","payload":{"call_id":"c-1","reason":"declined"}}"""
        )
        router.route(session, """{"v":1,"type":"call.hangup","id":"f-5","payload":{"call_id":"c-1"}}""")

        val (reject, hangup) = callClient.forwarded
        assertEquals("reject c-1", reject.description)
        assertEquals("declined", assertIs<RejectCallRequest>(reject.request).reason)
        assertEquals("hangup c-1", hangup.description)
        assertEquals(
            null,
            assertIs<HangupCallRequest>(hangup.request).reason,
            "no reason is a valid hangup — the service picks one"
        )
    }

    @Test
    fun `a malformed call frame never reaches call-service`() {
        val session = session()

        router.route(session, """{"v":1,"type":"call.accept","id":"f-6","payload":{"call_id":"c-1"}}""")

        assertEquals(ErrorCodes.BAD_FRAME, assertIs<OutboundFrame.Error>(session.nextFrame()).code)
        assertTrue(callClient.forwarded.isEmpty())
    }

    /**
     * A hand-written fake rather than a mock. The port is a Kotlin interface with non-null
     * parameters, and every Mockito matcher (`any`, `eq`, `capture`) returns null — which Kotlin
     * refuses to pass. Recording the calls is also what these assertions actually want.
     */
    private class RecordingCallClient : CallClient {

        data class Forwarded(val description: String, val request: Any)

        val forwarded = mutableListOf<Forwarded>()

        var answer: CallSignalResult = CallSignalResult.Accepted

        override fun invite(request: InviteCallRequest) = record("invite ${request.callId}", request)

        override fun accept(callId: String, request: AcceptCallRequest) = record("accept $callId", request)

        override fun reject(callId: String, request: RejectCallRequest) = record("reject $callId", request)

        override fun hangup(callId: String, request: HangupCallRequest) = record("hangup $callId", request)

        override fun ice(callId: String, request: IceCandidateRequest) = record("ice $callId", request)

        private fun record(description: String, request: Any): CallSignalResult {
            forwarded += Forwarded(description, request)
            return answer
        }
    }
}
