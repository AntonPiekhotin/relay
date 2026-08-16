package com.relay.common.dto

/**
 * Who is in a dialog. message-service → websocket-gateway, over `/internal`.
 *
 * The gateway needs this for presence and typing, and for nothing else: both are addressed by
 * dialog on the wire but relayed to *people*, and membership is message-service's data. The gateway
 * must not learn it any other way — reading `dialog_participants` directly would break the data
 * boundary, and letting the client name its own recipients would let it push frames to anyone.
 *
 * A caller that is not a participant is answered with **404**, not 403, so this cannot be used to
 * discover whether a guessed dialog id names a real conversation (`docs/ARCHITECTURE.md`
 * decision 32). That means a `Found` result doubles as the authorization answer.
 */
data class DialogParticipantsResponse(
    val dialogId: String,
    val participantIds: List<String>
)
