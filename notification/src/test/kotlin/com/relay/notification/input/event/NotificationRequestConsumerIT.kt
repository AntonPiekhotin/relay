package com.relay.notification.input.event

import com.relay.common.event.KafkaTopics
import com.relay.common.event.NotificationRequestedEvent
import com.relay.notification.PostgresTestcontainerConfig
import com.relay.notification.model.DeviceToken
import com.relay.notification.model.dto.RegisterDeviceTokenRequest
import com.relay.notification.output.push.PushMessage
import com.relay.notification.output.push.PushResult
import com.relay.notification.output.push.PushSender
import com.relay.notification.output.push.VoipPushSender
import com.relay.notification.service.DeviceTokenService
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import tools.jackson.databind.json.JsonMapper

/**
 * The step-2 pipeline against a real in-JVM broker: a push request on `notifications` reaches
 * every device the recipient registered, and only theirs — through the real consumer, the real
 * token store (Postgres, schema built by Flyway), and the [PushSender] port with a recording
 * double behind it.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        // Force the FCM adapter off regardless of what application.yaml says: tests talk to
        // the recording double, never to Google.
        "relay.push.fcm.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"
    ]
)
@Import(PostgresTestcontainerConfig::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.NOTIFICATIONS])
class NotificationRequestConsumerIT {

    @TestConfiguration
    class RecordingPushConfig {
        @Bean
        @Primary
        fun recordingPushSender() = RecordingPushSender()

        @Bean
        fun recordingVoipPushSender() = RecordingVoipPushSender()
    }

    class RecordingPushSender : PushSender {
        val sent = CopyOnWriteArrayList<Pair<DeviceToken, PushMessage>>()

        /** Devices whose next send reports a permanently dead token. */
        val deadDevices = CopyOnWriteArrayList<String>()

        override fun send(token: DeviceToken, message: PushMessage): PushResult {
            sent += token to message
            return if (token.deviceId in deadDevices) PushResult.TOKEN_DEAD else PushResult.SENT
        }
    }

    /** Stands in for the APNs adapter, which in production exists only when a .p8 key does. */
    class RecordingVoipPushSender : VoipPushSender {
        val sent = CopyOnWriteArrayList<Pair<DeviceToken, PushMessage>>()

        /** Devices whose next VoIP send reports a permanently dead *voip* token. */
        val deadVoipDevices = CopyOnWriteArrayList<String>()

        override fun send(token: DeviceToken, message: PushMessage): PushResult {
            sent += token to message
            return if (token.deviceId in deadVoipDevices) PushResult.TOKEN_DEAD else PushResult.SENT
        }
    }

    @Autowired private lateinit var deviceTokenService: DeviceTokenService
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var jsonMapper: JsonMapper
    @Autowired private lateinit var pushSender: RecordingPushSender
    @Autowired private lateinit var voipPushSender: RecordingVoipPushSender

    @BeforeTest
    fun reset() {
        pushSender.sent.clear()
        pushSender.deadDevices.clear()
        voipPushSender.sent.clear()
        voipPushSender.deadVoipDevices.clear()
    }

    private fun publish(request: NotificationRequestedEvent) {
        kafkaTemplate
            .send(KafkaTopics.NOTIFICATIONS, request.recipientId, jsonMapper.writeValueAsString(request))
            .get()
    }

    private fun messageRequest(recipientId: String, text: String = "hello there") =
        NotificationRequestedEvent(
            recipientId = recipientId,
            kind = NotificationRequestedEvent.KIND_MESSAGE_NEW,
            payload = mapOf(
                "messageId" to "m-1",
                "dialogId" to "d-1",
                "senderId" to "alice",
                "text" to text,
                "sentAt" to Instant.parse("2026-07-27T10:00:00Z").toString()
            ),
            requestedAt = Instant.parse("2026-07-27T10:00:00Z")
        )

    private fun register(userId: String, deviceId: String, platform: String = "android") {
        deviceTokenService.register(
            userId,
            RegisterDeviceTokenRequest(deviceId = deviceId, platform = platform, fcmToken = "fcm-$deviceId")
        )
    }

    @Test
    fun `pushes to every device of the recipient and nobody else`() {
        register("bob", "bob-phone")
        register("bob", "bob-tablet", platform = "ios")
        register("carol", "carol-phone")

        publish(messageRequest("bob"))

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(2, pushSender.sent.size)
        }
        val devices = pushSender.sent.map { it.first.deviceId }.toSet()
        assertEquals(setOf("bob-phone", "bob-tablet"), devices)
        pushSender.sent.forEach { (token, message) ->
            assertEquals("bob", token.userId)
            assertEquals("hello there", message.body)
            assertEquals("d-1", message.data["dialogId"])
            assertEquals("alice", message.data["senderId"])
        }
    }

    @Test
    fun `a recipient with no devices is dropped silently`() {
        publish(messageRequest("nobody-registered"))
        // Follow with a deliverable request; if the empty one had stalled or pushed, this shows it.
        register("dave", "dave-phone")
        publish(messageRequest("dave"))

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, pushSender.sent.size)
        }
        assertEquals("dave-phone", pushSender.sent.single().first.deviceId)
    }

    @Test
    fun `a long message is truncated to a preview`() {
        register("eve", "eve-phone")

        publish(messageRequest("eve", text = "x".repeat(500)))

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, pushSender.sent.size)
        }
        assertEquals(140, pushSender.sent.single().second.body.length)
    }

    @Test
    fun `malformed and unknown-kind requests do not stall the partition`() {
        kafkaTemplate.send(KafkaTopics.NOTIFICATIONS, "{ not a request }").get()
        publish(messageRequest("frank").copy(kind = "NO_SUCH_KIND"))
        register("frank", "frank-phone")
        publish(messageRequest("frank"))

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, pushSender.sent.size)
        }
        assertTrue(pushSender.sent.single().second.data["kind"] == NotificationRequestedEvent.KIND_MESSAGE_NEW)
    }

    @Test
    fun `a token FCM declares dead is pruned, the healthy device keeps its pushes`() {
        register("henry", "henry-old-phone")
        register("henry", "henry-new-phone")
        pushSender.deadDevices += "henry-old-phone"

        publish(messageRequest("henry"))

        // Both devices were attempted this time...
        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(2, pushSender.sent.size)
        }
        // ...but the dead one is gone from the store, so the next request skips it entirely.
        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(
                listOf("henry-new-phone"),
                deviceTokenService.tokensOf("henry").map { it.deviceId },
                "a token FCM declared UNREGISTERED must be deleted, not retried forever"
            )
        }

        pushSender.sent.clear()
        publish(messageRequest("henry"))
        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, pushSender.sent.size)
        }
        assertEquals("henry-new-phone", pushSender.sent.single().first.deviceId)
    }

    @Test
    fun `token re-registration replaces rather than duplicates`() {
        register("grace", "grace-phone")
        register("grace", "grace-phone")

        publish(messageRequest("grace"))

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, pushSender.sent.size)
        }
    }

    // ---- calls ----

    @Test
    fun `an incoming call is pushed data-only so the client can raise its own call UI`() {
        register("ivan", "ivan-phone")
        val ringExpiresAt = Instant.parse("2026-07-27T10:00:40Z")

        publish(
            NotificationRequestedEvent.incomingCall(
                recipientId = "ivan",
                callId = "call-1",
                callerId = "alice",
                media = "video",
                requestedAt = Instant.parse("2026-07-27T10:00:00Z"),
                ringExpiresAt = ringExpiresAt
            )
        )

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, pushSender.sent.size)
        }
        val message = pushSender.sent.single().second
        assertTrue(
            message.dataOnly,
            "a notification block would let the OS draw a banner instead of the app ringing"
        )
        assertEquals(NotificationRequestedEvent.KIND_INCOMING_CALL, message.data["kind"])
        assertEquals("call-1", message.data["callId"])
        assertEquals("alice", message.data["callerId"])
        assertEquals("video", message.data["media"])
        assertEquals(
            ringExpiresAt.toString(),
            message.data["ringExpiresAt"],
            "the client must be able to discard a push that arrives after the ring deadline"
        )
    }

    @Test
    fun `a missed call is an ordinary visible alert`() {
        register("julia", "julia-phone")

        publish(
            NotificationRequestedEvent.missedCall(
                recipientId = "julia",
                callId = "call-2",
                callerId = "alice",
                media = "audio",
                requestedAt = Instant.parse("2026-07-27T10:00:40Z")
            )
        )

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, pushSender.sent.size)
        }
        val message = pushSender.sent.single().second
        assertTrue(!message.dataOnly, "it happened already — there is nothing to answer")
        assertEquals("Missed call", message.title)
        assertEquals(NotificationRequestedEvent.KIND_MISSED_CALL, message.data["kind"])
        assertEquals("call-2", message.data["callId"])
        assertEquals("alice", message.data["callerId"])
    }

    // ---- APNs VoIP routing ----

    private fun registerWithVoip(userId: String, deviceId: String) {
        deviceTokenService.register(
            userId,
            RegisterDeviceTokenRequest(
                deviceId = deviceId, platform = "ios",
                fcmToken = "fcm-$deviceId", voipToken = "voip-$deviceId"
            )
        )
    }

    private fun incomingCall(recipientId: String, callId: String = "call-voip") =
        NotificationRequestedEvent.incomingCall(
            recipientId = recipientId,
            callId = callId,
            callerId = "alice",
            media = "audio",
            requestedAt = Instant.parse("2026-07-27T10:00:00Z"),
            ringExpiresAt = Instant.parse("2026-07-27T10:00:40Z"),
            callKind = NotificationRequestedEvent.CALL_KIND_GROUP
        )

    @Test
    fun `an incoming call rides VoIP where a device can take it, and FCM everywhere else`() {
        registerWithVoip("kate", "kate-iphone")
        register("kate", "kate-android") // android — no voip, whatever the columns say
        register("kate", "kate-ipad", platform = "ios") // ios but never registered a voip token

        publish(incomingCall("kate"))

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, voipPushSender.sent.size)
            assertEquals(2, pushSender.sent.size)
        }
        val (voipDevice, voipMessage) = voipPushSender.sent.single()
        assertEquals("kate-iphone", voipDevice.deviceId)
        assertEquals(
            Instant.parse("2026-07-27T10:00:40Z"), voipMessage.expiresAt,
            "APNs must discard the push at the ring deadline, not deliver it late"
        )
        assertEquals("group", voipMessage.data["callKind"])
        assertEquals(
            setOf("kate-android", "kate-ipad"),
            pushSender.sent.map { it.first.deviceId }.toSet(),
            "the device rung over VoIP is not also pushed over FCM"
        )
    }

    @Test
    fun `a dead voip token loses the column, keeps the row, and falls back to FCM`() {
        registerWithVoip("liam", "liam-iphone")
        voipPushSender.deadVoipDevices += "liam-iphone"

        publish(incomingCall("liam"))

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, voipPushSender.sent.size)
            assertEquals(1, pushSender.sent.size, "the device still gets the call, over FCM")
        }
        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            val stored = deviceTokenService.tokensOf("liam").single()
            assertEquals(null, stored.voipToken, "the dead voip token is cleared")
            assertEquals("fcm-liam-iphone", stored.fcmToken, "the FCM half of the row survives")
        }
    }

    @Test
    fun `a missed call never rides VoIP even where a voip token exists`() {
        registerWithVoip("mia", "mia-iphone")

        publish(
            NotificationRequestedEvent.missedCall(
                recipientId = "mia",
                callId = "call-3",
                callerId = "alice",
                media = "audio",
                requestedAt = Instant.parse("2026-07-27T10:00:40Z")
            )
        )

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertEquals(1, pushSender.sent.size)
        }
        assertEquals(0, voipPushSender.sent.size, "PushKit for a non-call push gets the app killed by iOS")
    }
}