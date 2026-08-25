package com.relay.call.repository

import com.relay.call.model.Call
import com.relay.call.model.CallKind
import com.relay.call.model.CallStatus
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface CallRepository : JpaRepository<Call, UUID> {

    fun findAllByKindAndStatusAndStartedAtBefore(
        kind: CallKind,
        status: CallStatus,
        startedAt: Instant
    ): List<Call>

    /**
     * `SELECT ... FOR UPDATE`, serializing every group-call transition on the call's own row.
     *
     * The optimistic version cannot do this job: two participants leaving at once each delete their
     * own `active_calls` row and then count what remains — and under READ COMMITTED each still sees
     * the other's uncommitted delete, so *neither* believes it is the last one out, and the call is
     * stranded ANSWERED with an empty room. A non-last leave never writes the `calls` row, so there
     * is no version bump for the race to trip over. The row lock makes "count the remaining" read
     * the truth. Direct calls stay on the optimistic version exactly as before.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Call c where c.id = :id")
    fun findWithLockById(@Param("id") id: UUID): Call?

    @Query(
        """
        select distinct c.id from Call c
        where c.kind = com.relay.call.model.CallKind.GROUP
          and c.status = com.relay.call.model.CallStatus.ANSWERED
          and c.startedAt < :startedBefore
          and exists (
              select p from CallParticipant p
              where p.callId = c.id and p.state = com.relay.call.model.ParticipantState.INVITED
          )
        """
    )
    fun findAnsweredGroupCallIdsWithPendingInvites(@Param("startedBefore") startedBefore: Instant): List<UUID>

    /** Live group calls, for reconciliation against the SFU's own view of the room. */
    fun findAllByKindAndStatusIn(kind: CallKind, statuses: Collection<CallStatus>): List<Call>

    /**
     * One page of a user's call log, newest first.
     *
     * Cursor, never offset: the caller passes the oldest call it already has and gets what precedes
     * it. `(started_at, id)` rather than `started_at` alone because two calls can start in the same
     * millisecond, and a cursor that cannot distinguish them either repeats a row or skips one.
     *
     * The first page passes a sentinel cursor rather than nulls. `:param is null` looks tidier but
     * leaves Postgres unable to infer the parameter's type ("could not determine data type of
     * parameter"), and casting it back into shape costs more clarity than the sentinel does. Both
     * comparisons are strict, so a sentinel above every real row matches everything.
     */
    @Query(
        """
        select c from Call c
        where exists (
            select p.userId from CallParticipant p where p.callId = c.id and p.userId = :userId
        )
        and (
            c.startedAt < :beforeStartedAt
            or (c.startedAt = :beforeStartedAt and c.id < :beforeId)
        )
        order by c.startedAt desc, c.id desc
        """
    )
    fun findHistory(
        @Param("userId") userId: String,
        @Param("beforeStartedAt") beforeStartedAt: Instant,
        @Param("beforeId") beforeId: UUID,
        limit: Limit
    ): List<Call>
}
