package com.relay.websocket.protocol

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameCodecTest {

    private val codec = FrameCodec()

    private fun decodeFailure(raw: String): FrameDecodeException =
        try {
            codec.decode(raw)
            throw AssertionError("expected FrameDecodeException for: $raw")
        } catch (ex: FrameDecodeException) {
            ex
        }

    // ---- encoding ----

    @Test
    fun `wraps outbound frames in the versioned envelope`() {
        val json = codec.encode(OutboundFrame.SessionConnected(userId = "bob", sessionId = "s-1"))

        assertTrue(json.contains("\"v\":1"), "was: $json")
        assertTrue(json.contains("\"type\":\"session.connected\""), "was: $json")
        assertTrue(json.contains("\"ts\":"), "was: $json")
        assertTrue(json.contains("\"user_id\":\"bob\""), "payload keys are snake_case; was: $json")
    }

    @Test
    fun `encodes an ack with spec payload keys`() {
        val json = codec.encode(
            OutboundFrame.Ack(
                clientMsgId = "c-1",
                messageId = "m-1",
                createdAt = Instant.parse("2026-07-26T10:00:00Z")
            )
        )

        assertTrue(json.contains("\"type\":\"ack\""), "was: $json")
        assertTrue(json.contains("\"client_msg_id\":\"c-1\""), "was: $json")
        assertTrue(json.contains("\"message_id\":\"m-1\""), "was: $json")
        assertTrue(json.contains("\"created_at\":\"2026-07-26T10:00:00Z\""), "instants as ISO strings; was: $json")
    }

    @Test
    fun `encodes errors with ref_id so the client can match the failed action`() {
        val json = codec.encode(OutboundFrame.Error(ErrorCodes.SEND_FAILED, "boom", refId = "c-9"))

        assertTrue(json.contains("\"type\":\"error\""), "was: $json")
        assertTrue(json.contains("\"ref_id\":\"c-9\""), "was: $json")
    }

    // ---- decoding ----

    @Test
    fun `decodes message-send, envelope id doubling as the client message id`() {
        val frame = codec.decode(
            """{"v":1,"type":"message.send","id":"c-1","ts":1730000000000,
                "payload":{"dialog_id":"d-1","text":"hello"}}"""
        )

        val send = assertIs<InboundFrame.MessageSend>(frame)
        assertEquals("c-1", send.id)
        assertEquals("d-1", send.dialogId)
        assertEquals("hello", send.text)
    }

    @Test
    fun `decodes a ping and keeps its id for the pong`() {
        val frame = codec.decode("""{"v":1,"type":"ping","id":"p-1"}""")

        assertEquals("p-1", assertIs<InboundFrame.Ping>(frame).id)
    }

    @Test
    fun `rejects a message-send without an envelope id`() {
        val ex = decodeFailure("""{"v":1,"type":"message.send","payload":{"dialog_id":"d-1","text":"hi"}}""")

        assertEquals(ErrorCodes.BAD_FRAME, ex.code)
    }

    @Test
    fun `rejects an unsupported protocol version with its own code`() {
        val ex = decodeFailure("""{"v":2,"type":"ping","id":"p-1"}""")

        assertEquals(ErrorCodes.UNSUPPORTED_VERSION, ex.code)
        assertEquals("p-1", ex.refId, "the client needs to know which frame was refused")
    }

    @Test
    fun `rejects a frame without a version`() {
        assertEquals(ErrorCodes.BAD_FRAME, decodeFailure("""{"type":"ping"}""").code)
    }

    @Test
    fun `rejects an unknown type`() {
        assertEquals(ErrorCodes.BAD_FRAME, decodeFailure("""{"v":1,"type":"no.such.type"}""").code)
    }

    @Test
    fun `rejects a payload that is not json`() {
        assertEquals(ErrorCodes.BAD_FRAME, decodeFailure("not json at all").code)
    }

    // ---- call signaling ----

    @Test
    fun `decodes a call invite, sdp relayed as an opaque string`() {
        val frame = codec.decode(
            """{"v":1,"type":"call.invite","id":"f-1","ts":1730000000000,
                "payload":{"call_id":"c-1","callee_id":"bob","media":"video",
                           "sdp":"v=0\r\no=- 1 1 IN IP4 0.0.0.0","dialog_id":"d-9"}}"""
        )

        val invite = assertIs<InboundFrame.CallInvite>(frame)
        assertEquals("c-1", invite.callId)
        assertEquals("bob", invite.calleeId)
        assertEquals("video", invite.media)
        assertEquals("v=0\r\no=- 1 1 IN IP4 0.0.0.0", invite.sdp)
        assertEquals("d-9", invite.dialogId)
    }

    @Test
    fun `a call invite carries no caller - identity comes from the session`() {
        val frame = codec.decode(
            """{"v":1,"type":"call.invite","id":"f-1",
                "payload":{"call_id":"c-1","callee_id":"bob","media":"audio","sdp":"v=0",
                           "caller_id":"mallory"}}"""
        )

        // Nothing on InboundFrame.CallInvite can hold it, which is the point: a claimed caller in
        // the payload is silently dropped rather than trusted.
        assertIs<InboundFrame.CallInvite>(frame)
    }

    @Test
    fun `decodes a call answer`() {
        val frame = codec.decode(
            """{"v":1,"type":"call.accept","id":"f-2","payload":{"call_id":"c-1","sdp":"v=0 answer"}}"""
        )

        val accept = assertIs<InboundFrame.CallAccept>(frame)
        assertEquals("c-1", accept.callId)
        assertEquals("v=0 answer", accept.sdp)
    }

    @Test
    fun `decodes a candidate as an opaque object`() {
        val frame = codec.decode(
            """{"v":1,"type":"call.ice","id":"f-3",
                "payload":{"call_id":"c-1","candidate":{"candidate":"candidate:1 1 udp","sdpMLineIndex":0}}}"""
        )

        val ice = assertIs<InboundFrame.CallIce>(frame)
        assertEquals("c-1", ice.callId)
        assertEquals("candidate:1 1 udp", ice.candidate["candidate"])
        assertEquals(0, ice.candidate["sdpMLineIndex"], "the candidate is passed through untouched")
    }

    @Test
    fun `decodes reject and hangup, whose reason is optional`() {
        val reject = assertIs<InboundFrame.CallReject>(
            codec.decode("""{"v":1,"type":"call.reject","id":"f-4","payload":{"call_id":"c-1"}}""")
        )
        assertEquals(null, reject.reason)

        val hangup = assertIs<InboundFrame.CallHangup>(
            codec.decode(
                """{"v":1,"type":"call.hangup","id":"f-5","payload":{"call_id":"c-1","reason":"busy"}}"""
            )
        )
        assertEquals("busy", hangup.reason)
    }

    @Test
    fun `rejects call frames missing a required payload field`() {
        val cases = listOf(
            """{"v":1,"type":"call.invite","id":"f","payload":{"callee_id":"bob","media":"audio","sdp":"v=0"}}""",
            """{"v":1,"type":"call.invite","id":"f","payload":{"call_id":"c","media":"audio","sdp":"v=0"}}""",
            """{"v":1,"type":"call.invite","id":"f","payload":{"call_id":"c","callee_id":"bob","sdp":"v=0"}}""",
            """{"v":1,"type":"call.invite","id":"f","payload":{"call_id":"c","callee_id":"bob","media":"audio"}}""",
            """{"v":1,"type":"call.accept","id":"f","payload":{"call_id":"c"}}""",
            """{"v":1,"type":"call.ice","id":"f","payload":{"call_id":"c"}}""",
            """{"v":1,"type":"call.ice","id":"f","payload":{"call_id":"c","candidate":{}}}""",
            """{"v":1,"type":"call.hangup","id":"f","payload":{}}"""
        )

        cases.forEach { raw ->
            assertEquals(ErrorCodes.BAD_FRAME, decodeFailure(raw).code, "should have been refused: $raw")
        }
    }

    @Test
    fun `rejects a call frame without an envelope id, since an error could not be attributed`() {
        val ex = decodeFailure(
            """{"v":1,"type":"call.invite","payload":{"call_id":"c","callee_id":"b","media":"audio","sdp":"v=0"}}"""
        )

        assertEquals(ErrorCodes.BAD_FRAME, ex.code)
    }

    @Test
    fun `rejects a call frame with no payload at all`() {
        assertEquals(ErrorCodes.BAD_FRAME, decodeFailure("""{"v":1,"type":"call.accept","id":"f"}""").code)
    }

    @Test
    fun `outbound call signals stay one opaque frame type`() {
        val json = codec.encode(
            OutboundFrame.CallSignal(
                callId = "c-1",
                fromUserId = "alice",
                signal = mapOf("verb" to "invite", "sdp" to "v=0")
            )
        )

        assertTrue(json.contains("\"type\":\"call.signal\""), "was: $json")
        assertTrue(json.contains("\"call_id\":\"c-1\""), "was: $json")
        assertTrue(json.contains("\"from_user_id\":\"alice\""), "was: $json")
        assertTrue(json.contains("\"verb\":\"invite\""), "map keys pass through unrenamed; was: $json")
    }
}