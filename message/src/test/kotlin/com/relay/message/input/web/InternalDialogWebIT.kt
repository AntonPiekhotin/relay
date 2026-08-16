package com.relay.message.input.web

import com.relay.message.PostgresTestcontainerConfig
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.containsInAnyOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

/**
 * The membership lookup the gateway uses for presence and typing.
 *
 * What only shows up at this layer: it answers without a token — `/internal` is service-to-service
 * and is not routed by the api-gateway — while still refusing a caller who is not in the dialog. Both
 * halves matter: the first is why the gateway can call it at all, the second is why that does not turn
 * a trusted `callerId` into a way to watch strangers.
 */
@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Import(PostgresTestcontainerConfig::class)
@AutoConfigureMockMvc
class InternalDialogWebIT {

    @Autowired private lateinit var mockMvc: MockMvc

    private val jsonMapper = JsonMapper.builder().build()

    private fun userId(role: String) = "$role-${UUID.randomUUID()}"

    private fun createDialog(vararg participantIds: String): String {
        val body = mockMvc.perform(
            post("/internal/api/v1/dialogs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"participantIds":${participantIds.joinToString("\",\"", "[\"", "\"]")}}""")
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return jsonMapper.readTree(body).get("id").asString()
    }

    private fun participants(dialogId: String, callerId: String): ResultActions =
        mockMvc.perform(get("/internal/api/v1/dialogs/$dialogId/participants").param("callerId", callerId))

    @Test
    fun `answers a participant with the whole membership, and needs no token`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialogId = createDialog(alice, bob)

        participants(dialogId, alice)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dialogId").value(dialogId))
            // The caller is included: the gateway subtracts itself to get the presence subjects.
            .andExpect(jsonPath("$.participantIds", containsInAnyOrder(alice, bob)))
    }

    @Test
    fun `refuses a caller who is not in the dialog, the same way it refuses one that does not exist`() {
        val dialogId = createDialog(userId("alice"), userId("bob"))

        // Both 404: a 403 for the first would confirm that a guessed dialog id names a real
        // conversation, which is exactly the enumeration the read paths refuse to allow.
        participants(dialogId, userId("mallory")).andExpect(status().isNotFound)
        participants(UUID.randomUUID().toString(), userId("mallory")).andExpect(status().isNotFound)
    }

    @Test
    fun `a malformed dialog id is a 400, not a 500`() {
        participants("not-a-uuid", userId("alice"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage[0]", org.hamcrest.Matchers.containsString("dialogId")))
    }
}
