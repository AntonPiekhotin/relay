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

    fun encode(frame: OutboundFrame): String {
        val type = when (frame) {
            is OutboundFrame.SessionConnected -> "session.connected"
            is OutboundFrame.Pong -> "pong"
            is OutboundFrame.Ack -> "ack"
            is OutboundFrame.MessageNew -> "message.new"
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