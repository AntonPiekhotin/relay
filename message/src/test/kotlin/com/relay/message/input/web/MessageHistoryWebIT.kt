package com.relay.message.input.web

import com.relay.common.dto.SendMessageRequest
import com.relay.common.event.KafkaTopics
import com.relay.message.PostgresTestcontainerConfig
import com.relay.message.service.DialogService
import com.relay.message.service.MessageService
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The HTTP layer of the history and dialog-list endpoints. What only shows up here: the caller is the
 * token's `sub` and never a query parameter, an anonymous request is refused, and — the part a client
 * is actually written against — the **JSON key names**.
 *
 * The key names are the contract (`docs/PROTOCOL.md` §5.1). A rename that the service tests would
 * happily accept silently breaks every client, so they are pinned here.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"
    ]
)
@Import(PostgresTestcontainerConfig::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.MESSAGES_DELIVERY])
@AutoConfigureMockMvc
class MessageHistoryWebIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dialogService: DialogService
    @Autowired private lateinit var messageService: MessageService

    private fun userId(role: String) = "$role-${UUID.randomUUID()}"

    private fun dialogOf(a: String, b: String): String = dialogService.openDirect(a, b).dialog.id

    private fun send(dialogId: String, senderId: String, text: String) =
        messageService.send(SendMessageRequest(UUID.randomUUID().toString(), dialogId, senderId, text))

    /** A null [callerId] sends the request anonymously; otherwise it carries a token with that `sub`. */
    private fun fetch(callerId: String?, path: String): ResultActions {
        val request = get(path)
        if (callerId != null) request.with(jwt().jwt { it.subject(callerId) })
        return mockMvc.perform(request)
    }

    @Test
    fun `returns history under the documented camelCase keys`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        send(dialog, alice, "hello bob")

        fetch(alice, "/api/v1/message/dialogs/$dialog/messages")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages", hasSize<Any>(1)))
            .andExpect(jsonPath("$.messages[0].messageId").exists())
            .andExpect(jsonPath("$.messages[0].dialogId").value(dialog))
            .andExpect(jsonPath("$.messages[0].senderId").value(alice))
            .andExpect(jsonPath("$.messages[0].text").value("hello bob"))
            // `createdAt`, not `sentAt`: the wire has said `created_at` since the first ack shipped.
            .andExpect(jsonPath("$.messages[0].createdAt").exists())
            .andExpect(jsonPath("$.messages[0].clientMsgId").exists())
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
    }

    @Test
    fun `redacts another sender's clientMsgId over the wire`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        send(dialog, alice, "from alice")

        fetch(bob, "/api/v1/message/dialogs/$dialog/messages")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages[0].clientMsgId").doesNotExist())
    }

    @Test
    fun `returns the dialog list under the documented camelCase keys`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        send(dialog, alice, "hi")

        fetch(bob, "/api/v1/message/dialogs")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dialogs", hasSize<Any>(1)))
            .andExpect(jsonPath("$.dialogs[0].dialogId").value(dialog))
            .andExpect(jsonPath("$.dialogs[0].type").value("direct"))
            .andExpect(jsonPath("$.dialogs[0].participantIds", containsInAnyOrder(alice, bob)))
            .andExpect(jsonPath("$.dialogs[0].lastMessageAt").exists())
            .andExpect(jsonPath("$.dialogs[0].unreadCount").value(1))
            .andExpect(jsonPath("$.dialogs[0].createdAt").exists())
    }

    @Test
    fun `pages backwards with the cursor from the previous page`() {
        val alice = userId("alice")
        val dialog = dialogOf(alice, userId("bob"))
        listOf("m1", "m2", "m3").forEach { send(dialog, alice, it) }

        val cursor = send(dialog, alice, "m4").message.id

        fetch(alice, "/api/v1/message/dialogs/$dialog/messages?before=$cursor&limit=2")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.messages[*].text", contains("m3", "m2")))
            .andExpect(jsonPath("$.nextCursor").exists())
    }

    @Test
    fun `serves a single dialog and refuses one the caller is not in`() {
        val alice = userId("alice")
        val dialog = dialogOf(alice, userId("bob"))

        fetch(alice, "/api/v1/message/dialogs/$dialog")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dialogId").value(dialog))

        // 404 rather than 403, so the endpoint cannot confirm that a guessed dialog id is real.
        fetch(userId("mallory"), "/api/v1/message/dialogs/$dialog")
            .andExpect(status().isNotFound)
    }

    @Test
    fun `rejects both cursors at once`() {
        val alice = userId("alice")
        val dialog = dialogOf(alice, userId("bob"))
        val id = send(dialog, alice, "m1").message.id

        fetch(alice, "/api/v1/message/dialogs/$dialog/messages?before=$id&after=$id")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `refuses anonymous requests to both endpoints`() {
        val dialog = dialogOf(userId("alice"), userId("bob"))

        fetch(callerId = null, path = "/api/v1/message/dialogs").andExpect(status().isUnauthorized)
        fetch(callerId = null, path = "/api/v1/message/dialogs/$dialog/messages")
            .andExpect(status().isUnauthorized)
    }
}
