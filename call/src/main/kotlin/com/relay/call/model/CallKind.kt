package com.relay.call.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

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
