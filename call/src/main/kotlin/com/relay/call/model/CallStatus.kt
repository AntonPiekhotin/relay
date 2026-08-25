package com.relay.call.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

enum class CallStatus(val wireValue: String) {

    RINGING("ringing"),

    ANSWERED("answered"),

    REJECTED("rejected"),

    MISSED("missed"),

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
