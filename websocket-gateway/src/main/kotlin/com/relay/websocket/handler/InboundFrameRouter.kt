package com.relay.websocket.handler

import com.relay.common.event.KafkaTopics
import com.relay.common.event.SendMessageCommand
import com.relay.websocket.protocol.ErrorCodes
import com.relay.websocket.protocol.FrameCodec
import com.relay.websocket.protocol.FrameDecodeException
import com.relay.websocket.protocol.InboundFrame
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

/**
 * Dispatches one inbound frame. Sends are handed to Kafka `messages.incoming` and the ack
 * arrives later via `messages.delivery` (ARCHITECTURE.md §13.1, §20.1) — nothing here waits.
 *
 * A frame the gateway cannot handle produces an `error` frame rather than closing the socket:
 * one bad frame should not cost the client its connection (§10.3).
 */
@Component
class InboundFrameRouter(
    private val codec: FrameCodec,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun route(session: RelaySession, raw: String): Mono<Void> {
        val frame = try {
            codec.decode(raw)
        } catch (ex: FrameDecodeException) {
            logger.debug("Rejected frame from session {}: {}", session.sessionId, ex.message)
            session.send(OutboundFrame.Error(ex.code, ex.message, ex.refId))
            return Mono.empty()
        }
        return when (frame) {
            is InboundFrame.Ping -> {
                session.send(OutboundFrame.Pong(frame.id))
                Mono.empty()
            }
            is InboundFrame.MessageSend -> send(session, frame)
        }
    }

    /**
     * Fire-and-forget into the queue; the client's ack comes from the delivery event once the
     * message is persisted. Only a failed hand-off produces an immediate error frame — that is
     * the client's cue to retry the same id over REST.
     */
    private fun send(session: RelaySession, frame: InboundFrame.MessageSend): Mono<Void> {
        val command = SendMessageCommand(
            clientMessageId = frame.id,
            dialogId = frame.dialogId,
            // From the authenticated session, never from the frame.
            senderId = session.userId,
            senderSessionId = session.sessionId,
            text = frame.text
        )
        kafkaTemplate
            .send(KafkaTopics.MESSAGES_INCOMING, command.dialogId, jsonMapper.writeValueAsString(command))
            .whenComplete { _, ex ->
                if (ex != null) {
                    logger.error("Could not queue send {} from session {}", frame.id, session.sessionId, ex)
                    session.send(
                        OutboundFrame.Error(ErrorCodes.SEND_FAILED, "Message could not be queued", frame.id)
                    )
                }
            }
        return Mono.empty()
    }
}