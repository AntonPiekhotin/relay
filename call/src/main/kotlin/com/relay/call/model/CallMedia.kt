package com.relay.call.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * What the call carries. It decides nothing on the server — media never touches a service — but a
 * client needs to know whether to raise a voice or a video UI before it answers, and the call log
 * needs to say which it was.
 */
enum class CallMedia(val wireValue: String) {

    AUDIO("audio"),
    VIDEO("video");

    companion object {

        fun ofWire(value: String): CallMedia = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown call media '$value'")
    }
}

@Converter(autoApply = true)
class CallMediaConverter : AttributeConverter<CallMedia, String> {

    override fun convertToDatabaseColumn(attribute: CallMedia?): String? = attribute?.wireValue

    override fun convertToEntityAttribute(dbData: String?): CallMedia? = dbData?.let(CallMedia::ofWire)
}
