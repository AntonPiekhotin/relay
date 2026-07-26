package com.relay.websocket.protocol

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class FrameCodec(
    private val jsonMapper: JsonMapper
) {

    fun encode(frame: OutboundFrame): String = jsonMapper.writeValueAsString(frame)

    /** Throws if [raw] is not valid JSON or carries an unknown `type`; callers turn that into an ERROR frame. */
    fun decode(raw: String): InboundFrame = jsonMapper.readValue(raw, InboundFrame::class.java)
}