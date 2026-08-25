package com.relay.message.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "relay.message")
data class MessageProperties(

    val historyPageSize: Int = 50,

    val maxHistoryPageSize: Int = 100,

    val groupMemberCap: Int = 50,

    val dialogPageSize: Int = 100,

    val maxDialogPageSize: Int = 100
)
