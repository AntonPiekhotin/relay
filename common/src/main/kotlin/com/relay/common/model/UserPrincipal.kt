package com.relay.common.model

data class UserPrincipal(
    val userId: String,
    val email: String?,
    val roles: Set<String>
)
