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

enum class PushResult {

    SENT,

    /**
     * The token is permanently invalid — app uninstalled, token rotated, or garbage. The caller
     * must delete it: keeping it means every future message wastes a doomed FCM call, and the
     * table slowly fills with corpses.
     */
    TOKEN_DEAD,

    /**
     * Transport hiccup (FCM unavailable, quota). Dropping is acceptable: the message is safe in
     * the database (Principle 1) and the recipient catches up on next open — so no retry
     * machinery lives here.
     */
    TRANSIENT_FAILURE
}

/**
 * Port for the push transport (ARCHITECTURE.md §16.1). The delivery pipeline is written against
 * this so the FCM integration is a matter of adding an adapter with real credentials — the
 * consumer, token lookup and per-device fan-out do not change.
 *
 * Implementations must not throw: one undeliverable device must not cost the other devices
 * their push. Failures are the [PushResult] outcomes.
 */
interface PushSender {

    fun send(token: DeviceToken, message: PushMessage): PushResult
}
