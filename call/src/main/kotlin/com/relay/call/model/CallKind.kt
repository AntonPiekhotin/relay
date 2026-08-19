package com.relay.call.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Which state machine owns the call. A [DIRECT] call is two parties and accept/reject/hangup over
 * WebSocket frames; a [GROUP] call is N parties and join/decline/leave over REST, with media on the
 * SFU instead of peer-to-peer. Stored rather than derived, because a group call with one invitee
 * has two participants exactly like a direct call.
 */
enum class CallKind(val wireValue: String) {

    DIRECT("direct"),
    GROUP("group");

    companion object {

        fun ofWire(value: String): CallKind = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown call kind '$value'")
    }
}

@Converter(autoApply = true)
class CallKindConverter : AttributeConverter<CallKind, String> {

    override fun convertToDatabaseColumn(attribute: CallKind?): String? = attribute?.wireValue

    override fun convertToEntityAttribute(dbData: String?): CallKind? = dbData?.let(CallKind::ofWire)
}
