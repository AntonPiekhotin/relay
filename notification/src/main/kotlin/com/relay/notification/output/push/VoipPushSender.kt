package com.relay.notification.output.push

import com.relay.notification.model.DeviceToken

/**
 * Port for the APNs VoIP transport — deliberately a *separate* port from [PushSender] rather than
 * a second implementation of it. The FCM-versus-logging switch is a `@Primary` swap on one flag,
 * and a VoIP sender joining that arrangement would have to lose it; keeping the ports apart lets
 * the consumer route explicitly: VoIP where a device can take it, FCM everywhere else.
 *
 * Same no-throw contract as [PushSender]: failures are [PushResult] outcomes. A [PushResult.TOKEN_DEAD]
 * here means the *voip* token is dead — the caller clears that column, never the row, because the
 * device's FCM token may be perfectly alive.
 */
interface VoipPushSender {

    fun send(token: DeviceToken, message: PushMessage): PushResult
}
