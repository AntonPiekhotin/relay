package com.relay.call.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * One participant's own position in a call, distinct from the call's status: an ANSWERED group
 * call has joined people, ringing invitees, and decliners all at once, and the call row cannot
 * say which is which.
 *
 * Group calls branch on it; direct calls stamp it for honesty but decide everything from
 * [CallStatus], exactly as before it existed.
 */
enum class ParticipantState(val wireValue: String) {

    /** Rung and not yet answered. The only state [DECLINED] and [MISSED] can be reached from. */
    INVITED("invited"),

    /** In the call, holding an `active_calls` row. */
    JOINED("joined"),

    /** Refused while it rang. May still join while the call lives — changing one's mind is legal. */
    DECLINED("declined"),

    /** Rang out. Server-decided, like a missed call. */
    MISSED("missed"),

    /** Was in the call and left, or the call ended. May rejoin while the call lives. */
    LEFT("left");

    companion object {

        fun ofWire(value: String): ParticipantState = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown participant state '$value'")
    }
}

@Converter(autoApply = true)
class ParticipantStateConverter : AttributeConverter<ParticipantState, String> {

    override fun convertToDatabaseColumn(attribute: ParticipantState?): String? = attribute?.wireValue

    override fun convertToEntityAttribute(dbData: String?): ParticipantState? =
        dbData?.let(ParticipantState::ofWire)
}
