package com.relay.user.input.web

import com.relay.common.dto.CreateUserRequest
import com.relay.user.repository.ContactRepository
import com.relay.user.repository.UserAvatarRepository
import com.relay.user.repository.UserRepository
import com.relay.user.service.UserService
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The HTTP layer of the profile endpoints — the only place the request constraints actually run, and
 * the only place that exercises "me is the token's `sub`". The service tests below this one call
 * [UserService] directly and so cannot see either.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:profileweb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ]
)
@AutoConfigureMockMvc
class ProfileWebIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var userService: UserService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var contactRepository: ContactRepository
    @Autowired private lateinit var avatarRepository: UserAvatarRepository

    @BeforeTest
    fun resetDatabase() {
        contactRepository.deleteAll()
        avatarRepository.deleteAll()
        userRepository.deleteAll()
        userService.create(
            CreateUserRequest(id = "alice", email = "alice@relay.test", firstName = "Alice", lastName = "Anderson")
        )
    }

    /** Stands in for a Keycloak-issued token: the decoder is bypassed, the `sub` claim is not. */
    private fun asAlice() = jwt().jwt { it.subject("alice") }

    private fun putProfile(body: String) =
        mockMvc.perform(
            put("/api/v1/user/me")
                .with(asAlice())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )

    @Test
    fun `replaces the profile of the token subject`() {
        putProfile("""{"firstName":"Alicia","lastName":"Zhu"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("alice"))
            .andExpect(jsonPath("$.firstName").value("Alicia"))

        assertEquals("Alicia", userRepository.findById("alice").orElseThrow().firstName)
    }

    @Test
    fun `refuses a blank name and says which field failed`() {
        putProfile("""{"firstName":"   ","lastName":"Zhu"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage[0]").value(org.hamcrest.Matchers.containsString("firstName")))

        assertEquals("Alice", userRepository.findById("alice").orElseThrow().firstName, "nothing was written")
    }

    @Test
    fun `refuses an over-long name`() {
        putProfile("""{"firstName":"${"x".repeat(200)}","lastName":"Zhu"}""")
            .andExpect(status().isBadRequest)
    }

    /**
     * The difference from PATCH, pinned: a partial body is not a half-applied update. Both properties
     * are non-nullable in Kotlin, so this fails inside Jackson before Bean Validation runs — which is
     * why the handler has to map [org.springframework.http.converter.HttpMessageNotReadableException]
     * to 400, or it would surface as a 500.
     */
    @Test
    fun `refuses a partial body`() {
        putProfile("""{"firstName":"Alicia"}""")
            .andExpect(status().isBadRequest)

        assertEquals("Anderson", userRepository.findById("alice").orElseThrow().lastName)
    }

    @Test
    fun `rejects a request with no token`() {
        mockMvc.perform(
            put("/api/v1/user/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"firstName":"Mallory","lastName":"Malicious"}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `me is resolved from the token, not from a parameter`() {
        userService.create(
            CreateUserRequest(id = "bob", email = "bob@relay.test", firstName = "Bob", lastName = "Brown")
        )

        mockMvc.perform(get("/api/v1/user/me").with(jwt().jwt { it.subject("bob") }))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("bob"))
    }
}