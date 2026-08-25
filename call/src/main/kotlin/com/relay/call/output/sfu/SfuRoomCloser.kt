package com.relay.call.output.sfu

import com.relay.call.model.dto.event.GroupCallTerminated
import com.relay.call.service.sfu.RoomDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class SfuRoomCloser(
    private val roomDirectory: RoomDirectory
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onGroupCallTerminated(event: GroupCallTerminated) {
        try {
            roomDirectory.closeRoom(event.callId.toString())
            logger.debug("Closed SFU room for ended call {}", event.callId)
        } catch (ex: Exception) {
            logger.warn("Could not close SFU room for call {} — the empty-room timeout will", event.callId, ex)
        }
    }
}
