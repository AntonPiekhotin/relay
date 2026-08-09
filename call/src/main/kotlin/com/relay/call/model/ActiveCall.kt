package com.relay.call.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.util.UUID
import org.springframework.data.domain.Persistable

/**
 * "This user is in a live call, and it is that one."
 *
 * The whole point is the primary key on [userId]. Both participants get a row in the same
 * transaction that creates the call, so a second invite touching either of them collides on the
 * key and comes back as USER_BUSY — including when two users dial each other in the same instant,
 * which an application-level "is this user busy?" query would let through.
 *
 * Rows exist only while a call is ringing or in progress; every terminal transition deletes them.
 */
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

    /**
     * Always new — these rows are inserted and deleted, never updated.
     *
     * This is load-bearing rather than an optimisation. With an assigned id and no version, Spring
     * Data would decide the entity is not new and call `merge()`, which reads the existing row and
     * *updates* it. That silently steals a busy user's row instead of failing, and the failure is
     * the entire mechanism.
     */
    @Transient
    override fun isNew(): Boolean = true
}
