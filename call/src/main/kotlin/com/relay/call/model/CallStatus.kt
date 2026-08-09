package com.relay.call.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Where a call is in its life. [wireValue] is what reaches the database and the client, lowercase
 * to match the rest of the wire contract; the enum name is an internal detail.
 *
 * `docs/DATA.md` §6.1 also listed an `initiated` state ahead of `ringing`. It was collapsed:
 * a call is created and its invite published in the same commit, so there is no interval in which
 * a call is initiated but not ringing, and a status value that never occurs is worse than no
 * status value. Reintroduce it the day the gateway reports back whether the invite reached a live
 * socket.
 */
enum class CallStatus(val wireValue: String) {

    /** Invited, awaiting an answer. The only state the ring timeout applies to. */
    RINGING("ringing"),

    /** Answered and in progress. Media is flowing peer-to-peer; no service sees it. */
    ANSWERED("answered"),

    /** Declined explicitly by the callee. */
    REJECTED("rejected"),

    /** Rang out without an answer. Decided server-side, never reported by a client. */
    MISSED("missed"),

    /** Hung up, whether or not it was ever answered. */
    ENDED("ended");

    val isTerminal: Boolean get() = this in TERMINAL

    companion object {

        val TERMINAL = setOf(REJECTED, MISSED, ENDED)

        fun ofWire(value: String): CallStatus = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown call status '$value'")
    }
}

@Converter(autoApply = true)
class CallStatusConverter : AttributeConverter<CallStatus, String> {

    override fun convertToDatabaseColumn(attribute: CallStatus?): String? = attribute?.wireValue

    override fun convertToEntityAttribute(dbData: String?): CallStatus? = dbData?.let(CallStatus::ofWire)
}
