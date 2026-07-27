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
}