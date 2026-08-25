package com.relay.call.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

enum class ParticipantState(val wireValue: String) {

    INVITED("invited"),

    JOINED("joined"),

    DECLINED("declined"),

    MISSED("missed"),

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
