package com.relay.call.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(
    name = "calls",
    indexes = [
        Index(name = "ix_calls_status_started_at", columnList = "status, started_at"),
        Index(name = "ix_calls_started_at", columnList = "started_at, id")
    ]
)
class Call(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "initiator", nullable = false, updatable = false, length = 64)
    val initiator: String,

    @Column(name = "type", nullable = false, updatable = false, length = 8)
    val media: CallMedia,

    /** Which state machine owns this row — see [CallKind]. */
    @Column(name = "kind", nullable = false, updatable = false, length = 8)
    val kind: CallKind = CallKind.DIRECT,

    /** Client-supplied and never validated — dialogs belong to message-service. */
    @Column(name = "dialog_id", updatable = false)
    val dialogId: UUID? = null,

    @Column(name = "status", nullable = false, length = 16)
    var status: CallStatus = CallStatus.RINGING,

    @Column(name = "started_at", nullable = false, updatable = false)
    val startedAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),

    @Column(name = "answered_at")
    var answeredAt: Instant? = null,

    @Column(name = "ended_at")
    var endedAt: Instant? = null,

    /** Talk time, not ring time — null for a call that was never answered. */
    @Column(name = "duration_s")
    var durationSeconds: Int? = null,

    @Column(name = "end_reason", length = 32)
    var endReason: String? = null,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null
) {

    fun terminate(status: CallStatus, reason: String, at: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)) {
        require(status.isTerminal) { "$status is not a terminal status" }
        this.status = status
        this.endedAt = at
        this.endReason = reason
        this.durationSeconds = answeredAt?.let { Duration.between(it, at).seconds.toInt() }
    }
}
