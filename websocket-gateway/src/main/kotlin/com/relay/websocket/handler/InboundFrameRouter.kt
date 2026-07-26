package com.relay.websocket.handler

import com.relay.common.dto.SendMessageRequest
import com.relay.websocket.client.MessageServiceClient
import com.relay.websocket.protocol.ErrorCode
import com.relay.websocket.protocol.FrameCodec
import com.relay.websocket.protocol.InboundFrame
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Dispatches one inbound frame.
 *
 * A frame the gateway cannot handle produces an ERROR frame rather than closing the socket: one
 * bad frame should not cost the client its connection.
 */
@Component
class InboundFrameRouter(
    private val codec: FrameCodec,
    private val messageServiceClient: MessageServiceClient
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun route(session: RelaySession, raw: String): Mono<Void> {
        val frame = try {
            codec.decode(raw)
        } catch (ex: Exception) {
            logger.debug("Rejected unparseable frame from session {}", session.sessionId, ex)
            session.send(OutboundFrame.Error(ErrorCode.BAD_FRAME, "Frame could not be parsed"))
            return Mono.empty()
        }
        return when (frame) {
            is InboundFrame.Ping -> {
                session.send(OutboundFrame.Pong(frame.nonce))
                Mono.empty()
            }
            is InboundFrame.MessageSend -> send(session, frame)
        }
    }

    /**
     * The stored message also comes back to this client as MESSAGE_NEW via Kafka, since senders
     * are among the recipients so their other devices see it. The ack is what confirms storage;
     * clients reconcile the two by clientMessageId.
     */
    private fun send(session: RelaySession, frame: InboundFrame.MessageSend): Mono<Void> =
        messageServiceClient
            .send(
                SendMessageRequest(
                    clientMessageId = frame.clientMessageId,
                    chatId = frame.chatId,
                    // From the authenticated session, never from the frame.
                    senderId = session.userId,
                    body = frame.body
                )
            )
            .doOnNext { stored ->
                session.send(
                    OutboundFrame.MessageAck(
                        clientMessageId = stored.clientMessageId,
                        id = stored.id,
                        chatId = stored.chatId,
                        sentAt = stored.sentAt
                    )
                )
            }
            .onErrorResume { ex ->
                logger.warn(
                    "Send {} from session {} failed: {}",
                    frame.clientMessageId, session.sessionId, ex.message
                )
                // Echoing clientMessageId is what lets the client retry this exact send over REST.
                session.send(
                    OutboundFrame.Error(
                        code = ErrorCode.SEND_FAILED,
                        message = ex.message ?: "Send failed",
                        clientMessageId = frame.clientMessageId
                    )
                )
                Mono.empty()
            }
            .then()
}