package com.relay.message.input.web

import com.relay.common.event.KafkaTopics
import com.relay.message.PostgresTestcontainerConfig
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The HTTP status vocabulary of group management: what only shows up at this layer is that the
 * caller is the token's `sub`, validation runs, and the 201/200/400/403/404/409/422 matrix reaches
 * the wire as documented. The invariants themselves are proven in `GroupDialogServiceIT`.
 *
 * EmbeddedKafka because every mutation announces after commit; without a broker the producer's
 * metadata fetch would stall each request.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"
    ]
)
@Import(PostgresTestcontainerConfig::class)
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.MESSAGES_DELIVERY])
class GroupDialogWebIT {

    @Autowired private lateinit var mockMvc: MockMvc

    private fun userId(role: String) = "$role-${UUID.randomUUID()}"

    private fun createGroup(callerId: String, memberIds: Collection<String>, title: String = "team"): String {
        val dialogId = UUID.randomUUID().toString()
        val members = memberIds.joinToString(",") { "\"$it\"" }
        post(callerId, "/api/v1/message/dialogs/group", """{"dialogId":"$dialogId","title":"$title","memberIds":[$members]}""")
            .andExpect(status().isCreated)
        return dialogId
    }

    private fun post(callerId: String?, path: String, body: String? = null): ResultActions {
        val request = post(path).contentType(MediaType.APPLICATION_JSON)
        body?.let { request.content(it) }
        if (callerId != null) request.with(jwt().jwt { it.subject(callerId) })
        return mockMvc.perform(request)
    }

    @Test
    fun `creates a group for the token subject and replays converge on it`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val dialogId = UUID.randomUUID().toString()
        val body = """{"dialogId":"$dialogId","title":"team","memberIds":["$bob"]}"""

        post(owner, "/api/v1/message/dialogs/group", body)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.dialogId").value(dialogId))
            .andExpect(jsonPath("$.type").value("group"))
            .andExpect(jsonPath("$.title").value("team"))
            .andExpect(jsonPath("$.ownerId").value(owner))
            .andExpect(jsonPath("$.participantIds", containsInAnyOrder(owner, bob)))

        post(owner, "/api/v1/message/dialogs/group", body).andExpect(status().isOk)
    }

    @Test
    fun `refuses anonymous and malformed creates`() {
        post(null, "/api/v1/message/dialogs/group", """{"dialogId":"${UUID.randomUUID()}","title":"t","memberIds":["x"]}""")
            .andExpect(status().isUnauthorized)

        post(userId("alice"), "/api/v1/message/dialogs/group", """{"dialogId":"${UUID.randomUUID()}","title":"","memberIds":["x"]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage[0]", containsString("title")))
    }

    @Test
    fun `the management matrix answers as documented`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val dialogId = createGroup(owner, listOf(bob))

        // Rename: owner 200, member 403, outsider 404.
        mockMvc.perform(
            put("/api/v1/message/dialogs/$dialogId/title")
                .contentType(MediaType.APPLICATION_JSON).content("""{"title":"renamed"}""")
                .with(jwt().jwt { it.subject(owner) })
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("renamed"))
        mockMvc.perform(
            put("/api/v1/message/dialogs/$dialogId/title")
                .contentType(MediaType.APPLICATION_JSON).content("""{"title":"coup"}""")
                .with(jwt().jwt { it.subject(bob) })
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            put("/api/v1/message/dialogs/$dialogId/title")
                .contentType(MediaType.APPLICATION_JSON).content("""{"title":"probe"}""")
                .with(jwt().jwt { it.subject(userId("mallory")) })
        ).andExpect(status().isNotFound)

        // Members: owner adds 200; removing a stranger 404; removing yourself-as-owner 400.
        val carol = userId("carol")
        post(owner, "/api/v1/message/dialogs/$dialogId/members", """{"userIds":["$carol"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participantIds", hasSize<Any>(3)))
        mockMvc.perform(
            delete("/api/v1/message/dialogs/$dialogId/members/${userId("stranger")}")
                .with(jwt().jwt { it.subject(owner) })
        ).andExpect(status().isNotFound)
        mockMvc.perform(
            delete("/api/v1/message/dialogs/$dialogId/members/$owner").with(jwt().jwt { it.subject(owner) })
        ).andExpect(status().isBadRequest)

        // Leave: member 204, owner 422.
        post(carol, "/api/v1/message/dialogs/$dialogId/leave").andExpect(status().isNoContent)
        post(owner, "/api/v1/message/dialogs/$dialogId/leave").andExpect(status().isUnprocessableEntity)

        // Delete: member 403, owner 204, and the group is gone.
        mockMvc.perform(delete("/api/v1/message/dialogs/$dialogId").with(jwt().jwt { it.subject(bob) }))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/api/v1/message/dialogs/$dialogId").with(jwt().jwt { it.subject(owner) }))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/message/dialogs/$dialogId").with(jwt().jwt { it.subject(owner) }))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `the read-state snapshot lists every member's cursor and hides from outsiders`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val dialogId = createGroup(owner, listOf(bob))

        // The creation seed gives every founding member an entry immediately.
        mockMvc.perform(get("/api/v1/message/dialogs/$dialogId/read-state").with(jwt().jwt { it.subject(bob) }))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries", hasSize<Any>(2)))
            .andExpect(jsonPath("$.entries[*].userId", containsInAnyOrder(owner, bob)))

        mockMvc.perform(
            get("/api/v1/message/dialogs/$dialogId/read-state").with(jwt().jwt { it.subject(userId("mallory")) })
        ).andExpect(status().isNotFound)
    }
}
