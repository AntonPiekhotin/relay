package com.relay.call.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class CallParticipantId(
    val callId: UUID = ZERO,
    val userId: String = ""
) : Serializable {

    companion object {
        /** Only ever used by the no-arg constructor JPA requires of an id class. */
        private val ZERO: UUID = UUID(0L, 0L)
    }
}

/**
 * Membership, as its own table rather than caller/callee columns on [Call], so a group call is a
 * third row instead of a migration.
 *
 * [joinedAt] is when this participant's media session began — creation time for the caller, answer
 * time for the callee — and stays null for a callee who never answered.
 */
@Entity
@Table(name = "call_participants")
@IdClass(CallParticipantId::class)
class CallParticipant(

    @Id
    @Column(name = "call_id", nullable = false, updatable = false)
    val callId: UUID,

    @Id
    @Column(name = "user_id", nullable = false, updatable = false, length = 64)
    val userId: String,

    @Column(name = "joined_at")
    var joinedAt: Instant? = null,

    @Column(name = "left_at")
    var leftAt: Instant? = null
)
