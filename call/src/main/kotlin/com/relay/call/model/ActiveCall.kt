package com.relay.call.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.util.UUID
import org.springframework.data.domain.Persistable

@Entity
@Table(name = "active_calls")
class ActiveCall(

    @Id
    @Column(name = "user_id", nullable = false, updatable = false, length = 64)
    val userId: String,

    @Column(name = "call_id", nullable = false)
    val callId: UUID
) : Persistable<String> {

    override fun getId(): String = userId

    @Transient
    override fun isNew(): Boolean = true
}
