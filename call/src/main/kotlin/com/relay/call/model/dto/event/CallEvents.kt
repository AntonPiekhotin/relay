package com.relay.call.model.dto.event

import com.relay.common.event.CallSignalEvent
import com.relay.common.event.NotificationRequestedEvent

/**
 * In-JVM events, raised by the service layer and turned into Kafka records by the output adapter.
 *
 * They live under `model` rather than `output.event` so the service layer never depends on an
 * output adapter's package — the same reason `message` keeps `MessagePersisted` here.
 *
 * The indirection is not ceremony: it lets the adapter hold the publish until the transaction
 * commits, so a signal is never relayed for a state change that rolled back. A callee whose
 * `accept` lost an optimistic-lock race must not have had an answer delivered to the caller.
 */
data class CallSignalRaised(val signal: CallSignalEvent)

data class CallNotificationRequested(val request: NotificationRequestedEvent)
