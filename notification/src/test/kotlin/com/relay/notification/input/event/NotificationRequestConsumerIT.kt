package com.relay.notification.input.event

import com.relay.common.event.KafkaTopics
import com.relay.common.event.NotificationRequestedEvent
import com.relay.notification.PostgresTestcontainerConfig
import com.relay.notification.model.DeviceToken
import com.relay.notification.model.dto.RegisterDeviceTokenRequest
import com.relay.notification.output.push.PushMessage
import com.relay.notification.output.push.PushResult
import com.relay.notification.output.push.PushSender
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

    @Autowired private lateinit var deviceTokenService: DeviceTokenService
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var jsonMapper: JsonMapper
    @Autowired private lateinit var pushSender: RecordingPushSender

    @BeforeTest
    fun reset() {
        pushSender.sent.clear()
        pushSender.deadDevices.clear()
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
}