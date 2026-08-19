package com.relay.websocket.input.handler

import com.relay.common.dto.AcceptCallRequest
import com.relay.common.dto.HangupCallRequest
import com.relay.common.dto.IceCandidateRequest
import com.relay.common.dto.InviteCallRequest
import com.relay.common.dto.RejectCallRequest
import com.relay.common.event.MarkReadCommand
import com.relay.common.event.SendMessageCommand
import com.relay.common.observability.RequestId
import com.relay.common.observability.RequestIdContext
import com.relay.websocket.output.event.MessageEventProducer
import com.relay.websocket.output.http.CallClient
import com.relay.websocket.output.http.CallSignalResult
import com.relay.websocket.presence.PresenceService
import com.relay.websocket.presence.PresenceSubscribeResult
import com.relay.websocket.protocol.ErrorCodes
import com.relay.websocket.protocol.FrameCodec
import com.relay.websocket.protocol.FrameDecodeException
import com.relay.websocket.protocol.InboundFrame
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Dispatches one inbound frame. Sends are handed to [MessageEventProducer] and the ack arrives
 * later via `messages.delivery` — nothing here waits.
 *
 * Call signals are the exception: they go to [CallClient] and this method blocks on the answer,
 * because a call that cannot start has to fail the client's frame now. Blocking is what the request
 * thread is for here — it is virtual, and the client is bounded by a short timeout.
 *
 * A frame the gateway cannot handle produces an `error` frame rather than closing the socket:
 * one bad frame should not cost the client its connection.
 */
@Component
class InboundFrameRouter(
    private val codec: FrameCodec,
    private val messageEventProducer: MessageEventProducer,
    private val callClient: CallClient,
    private val presenceService: PresenceService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun route(session: RelaySession, raw: String) {
        val frame = try {
            codec.decode(raw)
        } catch (ex: FrameDecodeException) {
            logger.debug("Rejected frame from session {}: {}", session.sessionId, ex.message)
            session.send(OutboundFrame.Error(ex.code, ex.message, ex.refId))
            return
        }
        // The envelope's own `id` is already documented as the client's correlation and idempotency
        // handle, so it is reused as the correlation id rather than inventing a parallel one — which
        // also means nothing is added to the frame, and the wire contract is untouched. Only Ping
        // may omit it. Prefixed so a client-supplied value can never be mistaken for a server-minted
        // id in Kibana.
        RequestIdContext.put(
            RequestId.MDC_REQUEST_ID,
            frame.id?.let { "ws-$it" } ?: RequestId.newId(),
        )
        when (frame) {
            is InboundFrame.Ping -> session.send(OutboundFrame.Pong(frame.id))
            is InboundFrame.MessageSend -> send(session, frame)
            is InboundFrame.MessageRead -> read(session, frame)
            is InboundFrame.PresenceSubscribe -> subscribePresence(session, frame)
            // Both are fire-and-forget: nothing is returned, not even on failure.
            is InboundFrame.PresenceUnsubscribe -> presenceService.unsubscribe(session, frame.dialogId)
            is InboundFrame.TypingStart -> presenceService.typing(session, frame.dialogId)
            is InboundFrame.CallInvite -> signal(session, frame.id) {
                callClient.invite(
                    InviteCallRequest(
                        callId = frame.callId,
                        // From the authenticated session, never from the frame.
                        callerId = session.userId,
                        calleeId = frame.calleeId,
                        sessionId = session.sessionId,
                        media = frame.media,
                        sdp = frame.sdp,
                        dialogId = frame.dialogId
                    )
                )
            }
            is InboundFrame.CallAccept -> signal(session, frame.id) {
                callClient.accept(
                    frame.callId,
                    AcceptCallRequest(session.userId, session.sessionId, frame.sdp)
                )
            }
            is InboundFrame.CallReject -> signal(session, frame.id) {
                callClient.reject(
                    frame.callId,
                    RejectCallRequest(session.userId, session.sessionId, frame.reason)
                )
            }
            is InboundFrame.CallHangup -> signal(session, frame.id) {
                callClient.hangup(
                    frame.callId,
                    HangupCallRequest(session.userId, session.sessionId, frame.reason)
                )
            }
            is InboundFrame.CallIce -> signal(session, frame.id) {
                callClient.ice(
                    frame.callId,
                    IceCandidateRequest(session.userId, session.sessionId, frame.candidate)
                )
            }
        }
    }

    /**
     * Forwards a call signal and turns a rejection into an error frame the client can attribute to
     * the frame that caused it. Success is silent: what the participants actually see arrives as a
     * `call.signal` frame off the topic, including for the sender.
     */
    private fun signal(session: RelaySession, refId: String, forward: () -> CallSignalResult) {
        when (val result = forward()) {
            is CallSignalResult.Accepted -> Unit
            is CallSignalResult.Rejected -> {
                logger.debug(
                    "Call signal from session {} rejected: {} {}",
                    session.sessionId, result.code, result.message
                )
                session.send(OutboundFrame.Error(result.code, result.message, refId))
            }
        }
    }

    /**
     * Only a failed hand-off produces an immediate error frame — that is the client's cue to
     * retry the same id over REST.
     */
    private fun send(session: RelaySession, frame: InboundFrame.MessageSend) {
        val command = SendMessageCommand(
            clientMessageId = frame.id,
            dialogId = frame.dialogId,
            // From the authenticated session, never from the frame.
            senderId = session.userId,
            senderSessionId = session.sessionId,
            text = frame.text
        )
        // Captured here, restored in the callback: this completes on Kafka's producer I/O thread with
        // an empty MDC, so anything the error path logs would otherwise be uncorrelated.
        val mdc = RequestIdContext.capture()
        messageEventProducer.publish(command).whenComplete { _, ex ->
            mdc.restoring {
                if (ex != null) {
                    session.send(
                        OutboundFrame.Error(ErrorCodes.SEND_FAILED, "Message could not be queued", frame.id)
                    )
                }
            }
        }
    }

    /**
     * The one presence frame that answers. A subscription that silently failed would leave the
     * client waiting for updates that are never coming, with no way to tell that from a peer who
     * simply has not changed state.
     *
     * It blocks on the membership lookup for the same reason a call signal does — this thread is
     * virtual and the client is bounded by a short timeout.
     */
    private fun subscribePresence(session: RelaySession, frame: InboundFrame.PresenceSubscribe) {
        when (val result = presenceService.subscribe(session, frame.dialogId)) {
            is PresenceSubscribeResult.Subscribed -> Unit
            is PresenceSubscribeResult.Rejected ->
                session.send(OutboundFrame.Error(result.code, result.message, frame.id))
        }
    }

    private fun read(session: RelaySession, frame: InboundFrame.MessageRead) {
        messageEventProducer.publishRead(
            MarkReadCommand(
                dialogId = frame.dialogId,
                readerId = session.userId,
                readerSessionId = session.sessionId,
                upToMessageId = frame.upToMessageId
            )
        )
    }
}