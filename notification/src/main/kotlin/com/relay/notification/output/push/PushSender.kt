package com.relay.notification.output.push

import com.relay.notification.model.DeviceToken
import java.time.Instant

/**
 * A push notification ready for transport: [title]/[body] for the visible part, [data] for what
 * the app reads when the user taps through (dialogId, messageId, ...).
 *
 * [dataOnly] suppresses the visible part, and it exists for calls. When a `notification` block is
 * present and the app is backgrounded, the Android SDK displays the banner itself and never calls
 * the app's message handler — so the app cannot raise a full-screen incoming-call UI with answer and
 * decline actions. Sending data only hands that decision back to the client. A plain chat message
 * wants the opposite, which is why this defaults to false.
 *
 * [voipPreferred] marks the one kind that should ride APNs VoIP where a device can take it — an
 * incoming call, which is the only push allowed to use PushKit at all (Apple kills apps that ring
 * CallKit for anything else). [expiresAt] becomes the transport-level expiry, because a call push
 * delivered after the ring timeout is worse than none.
 */
data class PushMessage(
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val dataOnly: Boolean = false,
    val voipPreferred: Boolean = false,
    val expiresAt: Instant? = null
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
 * Port for the push transport. The delivery pipeline is written against
 * this so the FCM integration is a matter of adding an adapter with real credentials — the
 * consumer, token lookup and per-device fan-out do not change.
 *
 * Implementations must not throw: one undeliverable device must not cost the other devices
 * their push. Failures are the [PushResult] outcomes.
 */
interface PushSender {

    fun send(token: DeviceToken, message: PushMessage): PushResult
}
