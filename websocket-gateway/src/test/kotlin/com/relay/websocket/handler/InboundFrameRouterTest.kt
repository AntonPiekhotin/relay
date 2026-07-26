package com.relay.websocket.handler

import com.relay.common.dto.MessageResponse
import com.relay.common.dto.SendMessageRequest
import com.relay.common.exception.RelayException
import com.relay.common.model.UserPrincipal
import com.relay.websocket.client.MessageServiceClient
import com.relay.websocket.protocol.ErrorCode
import com.relay.websocket.protocol.FrameCodec
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import com.relay.websocket.util.MessageServiceProperties
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** Records what the router asked message-service to store, and returns a canned outcome. */
private class FakeMessageServiceClient(
    private val outcome: Mono<MessageResponse>
) : MessageServiceClient(WebClient.create(), MessageServiceProperties(), JsonMapper.builder().build()) {

    var captured: SendMessageRequest? = null

    override fun send(request: SendMessageRequest): Mono<MessageResponse> {
        captured = request
        return outcome
    }
}

class InboundFrameRouterTest {

    private val codec = FrameCodec(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())

    private val stored = MessageResponse(
        id = "m-1",
        chatId = "chat-1",
        senderId = "alice",
        body = "hello",
        sentAt = Instant.parse("2026-07-26T10:00:00Z"),
        clientMessageId = "c-1"
    )

    private fun session(userId: String = "alice") =
        RelaySession("s-1", UserPrincipal(userId, null, emptySet()), 16)

    private fun routerWith(outcome: Mono<MessageResponse>): Pair<InboundFrameRouter, FakeMessageServiceClient> {
        val client = FakeMessageServiceClient(outcome)
        return InboundFrameRouter(codec, client) to client
    }

    @Test
    fun `answers a ping with a pong carrying the same nonce`() {
        val (router, _) = routerWith(Mono.empty())
        val session = session()

        router.route(session, """{"type":"PING","nonce":"n-7"}""").block()

        StepVerifier.create(session.frames)
            .assertNext { assertEquals("n-7", assertIs<OutboundFrame.Pong>(it).nonce) }
            .thenCancel()
            .verify()
    }

    @Test
    fun `takes the sender from the session and not from the frame`() {
        val (router, client) = routerWith(Mono.just(stored))
        val session = session(userId = "alice")

        // The frame even tries to claim a different sender; it must be ignored.
        router.route(
            session,
            """{"type":"MESSAGE_SEND","clientMessageId":"c-1","chatId":"chat-1","body":"hello","senderId":"mallory"}"""
        ).block()

        assertEquals("alice", client.captured?.senderId, "a client must not be able to send as someone else")
        assertEquals("c-1", client.captured?.clientMessageId)
        assertEquals("chat-1", client.captured?.chatId)
    }

    @Test
    fun `acknowledges a stored message`() {
        val (router, _) = routerWith(Mono.just(stored))
        val session = session()

        router.route(
            session,
            """{"type":"MESSAGE_SEND","clientMessageId":"c-1","chatId":"chat-1","body":"hello"}"""
        ).block()

        StepVerifier.create(session.frames)
            .assertNext {
                val ack = assertIs<OutboundFrame.MessageAck>(it)
                assertEquals("c-1", ack.clientMessageId)
                assertEquals("m-1", ack.id)
                assertEquals(Instant.parse("2026-07-26T10:00:00Z"), ack.sentAt)
            }
            .thenCancel()
            .verify()
    }

    @Test
    fun `reports a failed send against its clientMessageId so the client can retry over REST`() {
        val (router, _) = routerWith(Mono.error(RelayException(503, "message-service is down")))
        val session = session()

        router.route(
            session,
            """{"type":"MESSAGE_SEND","clientMessageId":"c-1","chatId":"chat-1","body":"hello"}"""
        ).block()

        StepVerifier.create(session.frames)
            .assertNext {
                val error = assertIs<OutboundFrame.Error>(it)
                assertEquals(ErrorCode.SEND_FAILED, error.code)
                assertEquals("c-1", error.clientMessageId, "without this the client cannot tell which send failed")
            }
            .thenCancel()
            .verify()
    }

    @Test
    fun `a failed send does not fail the stream, so the socket survives`() {
        val (router, _) = routerWith(Mono.error(RelayException(503, "message-service is down")))

        StepVerifier.create(
            router.route(
                session(),
                """{"type":"MESSAGE_SEND","clientMessageId":"c-1","chatId":"chat-1","body":"hello"}"""
            )
        ).verifyComplete()
    }

    @Test
    fun `answers an unparseable frame with BAD_FRAME`() {
        val (router, _) = routerWith(Mono.empty())
        val session = session()

        router.route(session, "not json at all").block()

        StepVerifier.create(session.frames)
            .assertNext { assertEquals(ErrorCode.BAD_FRAME, assertIs<OutboundFrame.Error>(it).code) }
            .thenCancel()
            .verify()
    }
}