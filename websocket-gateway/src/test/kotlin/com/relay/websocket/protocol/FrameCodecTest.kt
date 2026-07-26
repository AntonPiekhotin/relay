package com.relay.websocket.protocol

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class FrameCodecTest {

    private val codec = FrameCodec(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())

    @Test
    fun `encodes the type discriminator clients switch on`() {
        val json = codec.encode(OutboundFrame.Connected(userId = "bob", sessionId = "s-1"))

        assertTrue(json.contains("\"type\":\"CONNECTED\""), "was: $json")
        assertTrue(json.contains("\"userId\":\"bob\""), "was: $json")
    }

    @Test
    fun `encodes instants as strings rather than epoch numbers`() {
        val json = codec.encode(
            OutboundFrame.MessageNew(
                id = "m-1",
                chatId = "c-1",
                senderId = "alice",
                body = "hi",
                sentAt = Instant.parse("2026-07-26T10:00:00Z")
            )
        )

        assertTrue(json.contains("\"sentAt\":\"2026-07-26T10:00:00Z\""), "was: $json")
    }

    @Test
    fun `decodes an inbound frame by its type`() {
        val frame = codec.decode("""{"type":"PING","nonce":"n-1"}""")

        assertIs<InboundFrame.Ping>(frame)
        assertEquals("n-1", frame.nonce)
    }

    @Test
    fun `rejects an unknown inbound type`() {
        assertFailsWith<Exception> { codec.decode("""{"type":"NOT_A_REAL_TYPE"}""") }
    }

    @Test
    fun `rejects a payload that is not json`() {
        assertFailsWith<Exception> { codec.decode("this is not json") }
    }
}