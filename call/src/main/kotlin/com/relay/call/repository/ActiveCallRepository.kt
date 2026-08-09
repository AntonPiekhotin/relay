package com.relay.call.repository

import com.relay.call.model.ActiveCall
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ActiveCallRepository : JpaRepository<ActiveCall, String> {

    /** Releases both participants when a call terminates. */
    fun deleteAllByCallId(callId: UUID): Long
}
