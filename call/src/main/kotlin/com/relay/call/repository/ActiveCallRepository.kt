package com.relay.call.repository

import com.relay.call.model.ActiveCall
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ActiveCallRepository : JpaRepository<ActiveCall, String> {

    /** Releases both participants when a call terminates. */
    fun deleteAllByCallId(callId: UUID): Long

    /** Releases one participant leaving a group call that goes on without them. */
    fun deleteByUserIdAndCallId(userId: String, callId: UUID): Long

    fun countByCallId(callId: UUID): Long

    /**
     * The group-call join claim. `ON CONFLICT DO NOTHING` rather than catch-the-violation, because
     * join must tell three outcomes apart: claimed (1 row — proceed), already claimed by *this*
     * call (0 rows, same call_id — an idempotent re-join), and claimed by another call (0 rows —
     * USER_BUSY). A fired constraint violation would abort the transaction before the read-back
     * that distinguishes the last two. The primary key still decides; this only changes how the
     * verdict is read. Same discipline as the `ON CONFLICT ... WHERE` guard on `dialog_read_state`.
     */
    @Modifying
    @Query(
        value = "INSERT INTO active_calls (user_id, call_id) VALUES (:userId, :callId) ON CONFLICT (user_id) DO NOTHING",
        nativeQuery = true
    )
    fun claim(@Param("userId") userId: String, @Param("callId") callId: UUID): Int
}
