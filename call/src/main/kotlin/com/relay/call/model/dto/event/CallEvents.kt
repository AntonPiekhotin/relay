package com.relay.call.model.dto.event

import com.relay.common.event.CallSignalEvent
import com.relay.common.event.NotificationRequestedEvent

data class CallSignalRaised(val signal: CallSignalEvent)

data class CallNotificationRequested(val request: NotificationRequestedEvent)

data class GroupCallTerminated(val callId: java.util.UUID)
