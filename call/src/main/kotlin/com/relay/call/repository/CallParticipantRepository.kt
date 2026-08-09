package com.relay.call.repository

import com.relay.call.model.CallParticipant
import com.relay.call.model.CallParticipantId
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CallParticipantRepository : JpaRepository<CallParticipant, CallParticipantId> {

    fun findAllByCallId(callId: UUID): List<CallParticipant>

    fun findAllByCallIdIn(callIds: Collection<UUID>): List<CallParticipant>
}
