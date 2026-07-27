package com.relay.websocket.handler

import com.relay.common.event.KafkaTopics
import com.relay.common.event.SendMessageCommand
import com.relay.common.model.UserPrincipal
import com.relay.websocket.protocol.ErrorCodes
import com.relay.websocket.protocol.FrameCodec
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import reactor.test.StepVerifier
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class InboundFrameRouterTest {

    private val jsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate =
        mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>

    private val router = InboundFrameRouter(FrameCodec(), kafkaTemplate, jsonMapper)

    private fun session(userId: String = "alice") =
        RelaySession("s-1", UserPrincipal(userId, null, emptySet()), 16)

    private fun queueSucceeds() {
        `when`(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null as SendResult<String, String>?))
    }

    @Test
    fun `answers a ping with a pong echoing the frame id`() {
        val session = session()

        router.route(session, """{"v":1,"type":"ping","id":"p-7"}""").block()

        StepVerifier.create(session.frames)
            .assertNext { assertEquals("p-7", assertIs<OutboundFrame.Pong>(it).refId) }
            .thenCancel()
            .verify()
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
        ).block()

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
        ).block()

        session.complete()
        StepVerifier.create(session.frames).verifyComplete()
    }

    @Test
    fun `a failed queue hand-off produces SEND_FAILED with the frame id`() {
        `when`(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("broker down")))
        val session = session()

        router.route(
            session,
            """{"v":1,"type":"message.send","id":"c-1","payload":{"dialog_id":"d-1","text":"hello"}}"""
        ).block()

        StepVerifier.create(session.frames)
            .assertNext {
                val error = assertIs<OutboundFrame.Error>(it)
                assertEquals(ErrorCodes.SEND_FAILED, error.code)
                assertEquals("c-1", error.refId, "the client retries exactly this send over REST")
            }
            .thenCancel()
            .verify()
    }

    @Test
    fun `an unparseable frame gets BAD_FRAME and nothing reaches the queue`() {
        val session = session()

        router.route(session, "not json at all").block()

        StepVerifier.create(session.frames)
            .assertNext { assertEquals(ErrorCodes.BAD_FRAME, assertIs<OutboundFrame.Error>(it).code) }
            .thenCancel()
            .verify()
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    fun `a wrong protocol version is refused without touching the queue`() {
        val session = session()

        router.route(session, """{"v":99,"type":"message.send","id":"c-1","payload":{"dialog_id":"d","text":"x"}}""").block()

        StepVerifier.create(session.frames)
            .assertNext { assertEquals(ErrorCodes.UNSUPPORTED_VERSION, assertIs<OutboundFrame.Error>(it).code) }
            .thenCancel()
            .verify()
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString())
    }
}