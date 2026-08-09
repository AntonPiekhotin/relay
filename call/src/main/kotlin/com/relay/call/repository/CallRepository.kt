package com.relay.call.repository

import com.relay.call.model.Call
import com.relay.call.model.CallStatus
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface CallRepository : JpaRepository<Call, UUID> {

    /** The ring-timeout sweeper's query. Served by `ix_calls_status_started_at`. */
    fun findAllByStatusAndStartedAtBefore(status: CallStatus, startedAt: Instant): List<Call>

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
