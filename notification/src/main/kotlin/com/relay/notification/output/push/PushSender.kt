package com.relay.notification.output.push

import com.relay.notification.model.DeviceToken

/**
 * A push notification ready for transport: [title]/[body] for the visible part, [data] for what
 * the app reads when the user taps through (dialogId, messageId, ...).
 */
data class PushMessage(
    val title: String,
    val body: String,
    val data: Map<String, String>
)

/**
 * Port for the push transport (ARCHITECTURE.md §16.1). The delivery pipeline is written against
 * this so the FCM integration is a matter of adding an adapter with real credentials — the
 * consumer, token lookup and per-device fan-out do not change.
 *
 * Implementations must not throw for a single undeliverable device: one dead token must not
 * cost the other devices their push.
 */
interface PushSender {

    fun send(token: DeviceToken, message: PushMessage)
}