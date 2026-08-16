package com.relay.websocket.protocol

import java.time.Instant
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** Protocol version this gateway speaks. */
const val PROTOCOL_VERSION = 1

/** Decode failure carrying the error code and the offending frame's envelope id, if readable. */
class FrameDecodeException(
    val code: String,
    override val message: String,
    val refId: String? = null
) : RuntimeException(message)

/**
 * (De)serializes the versioned envelope `{v, type, id, ts, payload}`, with dot-namespaced types
 * and snake_case payload keys. The wire contract lives in `docs/PROTOCOL.md`.
 *
 * Wire format has its own mapper — snake_case is a client-facing contract and must not leak
 * into (or be broken by) the camelCase mapper used for internal Kafka events.
 */
@Component
class FrameCodec {

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build()

    private data class Envelope(
        val v: Int? = null,
        val type: String? = null,
        val id: String? = null,
        val ts: Long? = null,
        val payload: JsonNode? = null
    )

    private data class MessageSendPayload(val dialogId: String? = null, val text: String? = null)

    private data class MessageReadPayload(val dialogId: String? = null, val upToMessageId: String? = null)

    /** Shared by `presence.subscribe`, `presence.unsubscribe`, and `typing.start`. */
    private data class DialogRefPayload(val dialogId: String? = null)

    private data class CallInvitePayload(
        val callId: String? = null,
        val calleeId: String? = null,
        val media: String? = null,
        val sdp: String? = null,
        val dialogId: String? = null
    )

    private data class CallAcceptPayload(val callId: String? = null, val sdp: String? = null)

    /** Serves both `call.reject` and `call.hangup`, whose payloads are the same shape. */
    private data class CallReasonPayload(val callId: String? = null, val reason: String? = null)

    private data class CallIcePayload(
        val callId: String? = null,
        val candidate: Map<String, Any?>? = null
    )

    fun decode(raw: String): InboundFrame {
        val envelope = try {
            mapper.readValue(raw, Envelope::class.java)
        } catch (ex: Exception) {
            throw FrameDecodeException(ErrorCodes.BAD_FRAME, "Frame is not a valid envelope")
        }
        val version = envelope.v
            ?: throw FrameDecodeException(ErrorCodes.BAD_FRAME, "Envelope field 'v' is required", envelope.id)
        if (version != PROTOCOL_VERSION) {
            throw FrameDecodeException(
                ErrorCodes.UNSUPPORTED_VERSION,
                "Protocol version $version is not supported; this server speaks v$PROTOCOL_VERSION",
                envelope.id
            )
        }
        return when (envelope.type) {
            "ping" -> InboundFrame.Ping(envelope.id, envelope.ts)
            "message.send" -> messageSend(envelope)
            "message.read" -> messageRead(envelope)
            "presence.subscribe" ->
                dialogRef(envelope, "presence.subscribe", InboundFrame::PresenceSubscribe)
            "presence.unsubscribe" ->
                dialogRef(envelope, "presence.unsubscribe", InboundFrame::PresenceUnsubscribe)
            "typing.start" -> dialogRef(envelope, "typing.start", InboundFrame::TypingStart)
            "call.invite" -> callInvite(envelope)
            "call.accept" -> callAccept(envelope)
            "call.reject" -> callReject(envelope)
            "call.ice" -> callIce(envelope)
            "call.hangup" -> callHangup(envelope)
            null -> throw FrameDecodeException(ErrorCodes.BAD_FRAME, "Envelope field 'type' is required", envelope.id)
            else -> throw FrameDecodeException(ErrorCodes.BAD_FRAME, "Unknown frame type '${envelope.type}'", envelope.id)
        }
    }

    private fun messageSend(envelope: Envelope): InboundFrame.MessageSend {
        // The envelope id doubles as the client message id, so it is mandatory here: without it
        // there is no idempotency key and no way to correlate the ack.
        val id = envelope.id?.takeIf { it.isNotBlank() }
            ?: throw FrameDecodeException(ErrorCodes.BAD_FRAME, "message.send requires an envelope 'id'")
        val payload = envelope.payload
            ?.let { mapper.treeToValue(it, MessageSendPayload::class.java) }
            ?: throw FrameDecodeException(ErrorCodes.BAD_FRAME, "message.send requires a payload", id)
        return InboundFrame.MessageSend(
            id = id,
            ts = envelope.ts,
            dialogId = payload.dialogId?.takeIf { it.isNotBlank() }
                ?: throw FrameDecodeException(ErrorCodes.BAD_FRAME, "payload.dialog_id is required", id),
            text = payload.text?.takeIf { it.isNotBlank() }
                ?: throw FrameDecodeException(ErrorCodes.BAD_FRAME, "payload.text is required", id)
        )
    }

    private fun messageRead(envelope: Envelope): InboundFrame.MessageRead {
        val id = requireEnvelopeId(envelope, "message.read")
        val payload = payload(envelope, id, "message.read", MessageReadPayload::class.java)
        return InboundFrame.MessageRead(
            id = id,
            ts = envelope.ts,
            dialogId = payload.dialogId.required("dialog_id", id),
            upToMessageId = payload.upToMessageId.required("up_to_message_id", id)
        )
    }

    private fun callInvite(envelope: Envelope): InboundFrame.CallInvite {
        val id = requireEnvelopeId(envelope, "call.invite")
        val payload = payload(envelope, id, "call.invite", CallInvitePayload::class.java)
        return InboundFrame.CallInvite(
            id = id,
            ts = envelope.ts,
            callId = payload.callId.required("call_id", id),
            calleeId = payload.calleeId.required("callee_id", id),
            media = payload.media.required("media", id),
            sdp = payload.sdp.required("sdp", id),
            dialogId = payload.dialogId?.takeIf { it.isNotBlank() }
        )
    }

    private fun callAccept(envelope: Envelope): InboundFrame.CallAccept {
        val id = requireEnvelopeId(envelope, "call.accept")
        val payload = payload(envelope, id, "call.accept", CallAcceptPayload::class.java)
        return InboundFrame.CallAccept(
            id = id,
            ts = envelope.ts,
            callId = payload.callId.required("call_id", id),
            sdp = payload.sdp.required("sdp", id)
        )
    }

    private fun callReject(envelope: Envelope): InboundFrame.CallReject {
        val id = requireEnvelopeId(envelope, "call.reject")
        val payload = payload(envelope, id, "call.reject", CallReasonPayload::class.java)
        return InboundFrame.CallReject(
            id = id,
            ts = envelope.ts,
            callId = payload.callId.required("call_id", id),
            reason = payload.reason?.takeIf { it.isNotBlank() }
        )
    }

    private fun callHangup(envelope: Envelope): InboundFrame.CallHangup {
        val id = requireEnvelopeId(envelope, "call.hangup")
        val payload = payload(envelope, id, "call.hangup", CallReasonPayload::class.java)
        return InboundFrame.CallHangup(
            id = id,
            ts = envelope.ts,
            callId = payload.callId.required("call_id", id),
            reason = payload.reason?.takeIf { it.isNotBlank() }
        )
    }

    private fun callIce(envelope: Envelope): InboundFrame.CallIce {
        val id = requireEnvelopeId(envelope, "call.ice")
        val payload = payload(envelope, id, "call.ice", CallIcePayload::class.java)
        val candidate = payload.candidate?.takeIf { it.isNotEmpty() }
            ?: throw FrameDecodeException(ErrorCodes.BAD_FRAME, "payload.candidate is required", id)
        return InboundFrame.CallIce(
            id = id,
            ts = envelope.ts,
            callId = payload.callId.required("call_id", id),
            candidate = candidate
        )
    }

    private fun <T : InboundFrame> dialogRef(
        envelope: Envelope,
        type: String,
        create: (String, Long?, String) -> T
    ): T {
        val id = requireEnvelopeId(envelope, type)
        val payload = payload(envelope, id, type, DialogRefPayload::class.java)
        return create(id, envelope.ts, payload.dialogId.required("dialog_id", id))
    }

    private fun requireEnvelopeId(envelope: Envelope, type: String): String =
        envelope.id?.takeIf { it.isNotBlank() }
            ?: throw FrameDecodeException(ErrorCodes.BAD_FRAME, "$type requires an envelope 'id'")

    private fun <T> payload(envelope: Envelope, id: String, type: String, target: Class<T>): T =
        envelope.payload?.let { mapper.treeToValue(it, target) }
            ?: throw FrameDecodeException(ErrorCodes.BAD_FRAME, "$type requires a payload", id)

    private fun String?.required(field: String, refId: String): String =
        this?.takeIf { it.isNotBlank() }
            ?: throw FrameDecodeException(ErrorCodes.BAD_FRAME, "payload.$field is required", refId)

    fun encode(frame: OutboundFrame): String {
        val type = when (frame) {
            is OutboundFrame.SessionConnected -> "session.connected"
            is OutboundFrame.Pong -> "pong"
            is OutboundFrame.Ack -> "ack"
            is OutboundFrame.MessageNew -> "message.new"
            is OutboundFrame.MessageRead -> "message.read"
            is OutboundFrame.PresenceUpdate -> "presence.update"
            is OutboundFrame.TypingStart -> "typing.start"
            is OutboundFrame.Notification -> "notification.new"
            is OutboundFrame.CallSignal -> "call.signal"
            is OutboundFrame.Error -> "error"
        }
        return mapper.writeValueAsString(
            mapOf(
                "v" to PROTOCOL_VERSION,
                "type" to type,
                "ts" to Instant.now().toEpochMilli(),
                "payload" to frame
            )
        )
    }
}