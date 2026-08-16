package com.relay.websocket.presence

import com.relay.websocket.output.http.DialogMembershipClient
import com.relay.websocket.output.http.DialogMembershipResult
import com.relay.websocket.protocol.ErrorCodes

/**
 * A hand-written fake rather than a mock, for the same reason [com.relay.websocket.handler
 * .InboundFrameRouterTest] uses one for `CallClient`: the port has non-null Kotlin parameters and
 * every Mockito matcher returns null. Counting the calls is also what the cache tests want.
 */
class StubDialogMembershipClient(
    private val membership: MutableMap<String, List<String>> = mutableMapOf()
) : DialogMembershipClient {

    var lookups = 0
        private set

    fun withDialog(dialogId: String, vararg participantIds: String): StubDialogMembershipClient =
        apply { membership[dialogId] = participantIds.toList() }

    override fun participants(dialogId: String, callerId: String): DialogMembershipResult {
        lookups++
        val participants = membership[dialogId]
        // Mirrors the real endpoint: "not yours" and "does not exist" are the same answer.
        return if (participants == null || callerId !in participants) {
            DialogMembershipResult.Rejected(ErrorCodes.DIALOG_NOT_FOUND, "Dialog not found")
        } else {
            DialogMembershipResult.Found(participants)
        }
    }
}
