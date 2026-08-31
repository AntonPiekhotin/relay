package com.relay.call.service

import com.relay.call.PostgresTestcontainerConfig
import com.relay.call.repository.ActiveCallRepository
import com.relay.common.dto.AcceptCallRequest
import com.relay.common.dto.InviteCallRequest
import com.relay.common.event.CallSignalEvent
import com.relay.common.event.CallSignalKeys
import com.relay.common.event.CallSignalVerbs
import com.relay.common.event.KafkaTopics
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import tools.jackson.databind.json.JsonMapper

/**
 * The disconnect sweep, end to end: an answered direct call whose participant's socket is gone
 * must be ended by the server — the vanished client never sends its hangup, and until somebody
 * ends the call both `active_calls` rows sit there and neither user can place or take a call.
 *
 * `disconnect-grace: 0s` makes one observation enough, so the sweep is driven by hand instead of
 * by waiting; the grace clock itself is [DisconnectTrackerTest]'s job. Both reconcile-shaped
 * schedules are parked at 1h so no sweep fires mid-test. The gateway is a stub bean: what it
 * would answer is exactly what each test arranges.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "relay.call.sweep-interval=1h",
        "relay.call.reconcile-interval=1h",
        "relay.call.disconnect-grace=0s"
    ]
)
@Import(PostgresTestcontainerConfig::class, DirectCallDisconnectIT.StubGateway::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.CALL_SIGNAL, KafkaTopics.NOTIFICATIONS])
class DirectCallDisconnectIT {

    class StubSessionDirectory : SessionDirectory {
        val online = mutableSetOf<String>()
        var unreachable = false

        override fun onlineAmong(userIds: Collection<String>): Set<String>? =
            if (unreachable) null else userIds.filterTo(mutableSetOf()) { it in online }
    }

    @TestConfiguration
    class StubGateway {
        @Bean
        @Primary
        fun stubSessionDirectory() = StubSessionDirectory()
    }

    @Autowired private lateinit var callService: CallService
    @Autowired private lateinit var sweeper: CallSweeper
    @Autowired private lateinit var gateway: StubSessionDirectory
    @Autowired private lateinit var activeCallRepository: ActiveCallRepository
    @Autowired private lateinit var broker: EmbeddedKafkaBroker
    @Autowired private lateinit var jsonMapper: JsonMapper

    private lateinit var consumer: Consumer<String, String>

    @BeforeTest
    fun subscribe() {
        gateway.online.clear()
        gateway.unreachable = false
        val props = KafkaTestUtils.consumerProps(broker, "disconnect-it-${UUID.randomUUID()}", true)
            .toMutableMap()
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        consumer = DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer())
            .createConsumer()
        consumer.subscribe(listOf(KafkaTopics.CALL_SIGNAL))
        consumer.poll(Duration.ofMillis(200))
    }

    @AfterTest
    fun closeConsumer() {
        consumer.close()
    }

    // ---- helpers ----

    private fun answeredCall(caller: String, callee: String): String {
        gateway.online.addAll(listOf(caller, callee))
        val call = callService.invite(
            InviteCallRequest(
                callId = UUID.randomUUID().toString(),
                callerId = caller,
                calleeId = callee,
                sessionId = "sess-$caller",
                media = "audio",
                sdp = "v=0\r\no=- offer"
            )
        )
        callService.accept(call.id, AcceptCallRequest(callee, "sess-$callee", "v=0 answer"))
        return call.id
    }

    private fun signals(): List<CallSignalEvent> {
        val collected = mutableListOf<CallSignalEvent>()
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var quietPolls = 0
        while (quietPolls < 2 && System.nanoTime() < deadline) {
            val records = consumer.poll(Duration.ofMillis(300))
            if (records.isEmpty) {
                quietPolls++
                continue
            }
            quietPolls = 0
            records.forEach { collected += jsonMapper.readValue(it.value(), CallSignalEvent::class.java) }
        }
        return collected
    }

    /**
     * Scoped to one call on purpose: `disconnect-grace: 0s` means a sweep also ends whatever
     * answered calls earlier tests left behind, and their hangups must not count here.
     */
    private fun List<CallSignalEvent>.hangupsFor(callId: String): List<CallSignalEvent> =
        filter { it.callId == callId && it.signal[CallSignalKeys.VERB] == CallSignalVerbs.HANGUP }

    // ---- tests ----

    @Test
    fun `a participant who closed their tab ends the call and frees both users`() {
        val callId = answeredCall(caller = "tab-alice", callee = "tab-bob")
        signals() // drain the setup traffic

        gateway.online.remove("tab-bob")
        sweeper.reconcileDisconnected()

        val hangups = signals().hangupsFor(callId)
        assertEquals(1, hangups.size, "the surviving side is told the call is over")
        assertEquals(listOf("tab-alice"), hangups.single().recipientIds)
        assertEquals("tab-bob", hangups.single().fromUserId)
        assertEquals("disconnected", hangups.single().signal[CallSignalKeys.REASON])

        assertTrue(activeCallRepository.findById("tab-alice").isEmpty, "the caller is freed")
        assertTrue(activeCallRepository.findById("tab-bob").isEmpty, "the vanished callee is freed")
        answeredCall(caller = "tab-alice", callee = "tab-carol") // both can call again
    }

    @Test
    fun `the sweep is idempotent - a second pass has nothing to end`() {
        val callId = answeredCall(caller = "idem-alice", callee = "idem-bob")
        signals()

        gateway.online.remove("idem-bob")
        sweeper.reconcileDisconnected()
        sweeper.reconcileDisconnected()

        assertEquals(1, signals().hangupsFor(callId).size, "one hangup, not one per pass")
    }

    @Test
    fun `a ringing call is the ring timeout's business, not this sweep's`() {
        val callId = UUID.randomUUID().toString()
        callService.invite(
            InviteCallRequest(
                callId = callId,
                callerId = "ring-alice",
                calleeId = "ring-bob",
                sessionId = "sess-ring-alice",
                media = "audio",
                sdp = "v=0 offer"
            )
        )
        signals()

        sweeper.reconcileDisconnected() // nobody is online, but the call only rings

        assertTrue(signals().hangupsFor(callId).isEmpty(), "a ringing call must not be hung up")
        assertTrue(activeCallRepository.findById("ring-bob").isPresent, "the claim stands until the ring settles")
    }

    @Test
    fun `an unreachable gateway skips the pass rather than reading as everybody-offline`() {
        val callId = answeredCall(caller = "down-alice", callee = "down-bob")
        signals()

        gateway.unreachable = true
        sweeper.reconcileDisconnected()

        assertTrue(signals().hangupsFor(callId).isEmpty(), "no observation, no verdict")
        assertTrue(activeCallRepository.findById("down-alice").isPresent, "the call goes on")
    }
}
