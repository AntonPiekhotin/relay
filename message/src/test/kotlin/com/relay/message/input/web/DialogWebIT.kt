package com.relay.message.input.web

import com.relay.message.PostgresTestcontainerConfig
import java.util.UUID
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The HTTP layer of the one client-facing endpoint. What only shows up here: the caller is the
 * token's `sub` rather than anything in the body, the request constraints actually run, and an
 * anonymous request is refused — the service tests call [DialogService] directly and see none of it.
 */
@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Import(PostgresTestcontainerConfig::class)
@AutoConfigureMockMvc
class DialogWebIT {

    @Autowired private lateinit var mockMvc: MockMvc

    private fun userId(role: String) = "$role-${UUID.randomUUID()}"

    /** A null [callerId] sends the request anonymously; otherwise it carries a token with that `sub`. */
    private fun openDialog(callerId: String?, body: String): ResultActions {
        val request = post("/api/v1/message/dialogs")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
        // Stands in for a Keycloak-issued token: the decoder is bypassed, the `sub` claim is not.
        if (callerId != null) {
            request.with(jwt().jwt { it.subject(callerId) })
        }
        return mockMvc.perform(request)
    }

    @Test
    fun `opens a dialog for the token subject and reports a repeat as pre-existing`() {
        val alice = userId("alice")
        val bob = userId("bob")

        openDialog(alice, """{"peerId":"$bob"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.type").value("direct"))
            .andExpect(jsonPath("$.participantIds", org.hamcrest.Matchers.containsInAnyOrder(alice, bob)))

        openDialog(alice, """{"peerId":"$bob"}""")
            .andExpect(status().isOk)
    }

    @Test
    fun `refuses an anonymous request`() {
        openDialog(callerId = null, body = """{"peerId":"${userId("bob")}"}""")
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `refuses a blank peerId and says which field failed`() {
        openDialog(userId("alice"), """{"peerId":"  "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage[0]", org.hamcrest.Matchers.containsString("peerId")))
    }
}
