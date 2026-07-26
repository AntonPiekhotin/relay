package com.relay.websocket.security

import com.relay.common.model.UserPrincipal
import com.relay.websocket.util.WebSocketProperties
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

@Component
class JwtUserPrincipalMapper(
    private val props: WebSocketProperties
) {

    /**
     * Returns null when the authentication is not a validated Keycloak JWT, or carries no `sub`
     * claim — without a subject there is no user to key sessions by, so the socket is refused.
     */
    fun map(authentication: Authentication): UserPrincipal? {
        val jwt = (authentication as? JwtAuthenticationToken)?.token ?: return null
        val subject = jwt.subject ?: return null
        return UserPrincipal(
            userId = subject,
            email = jwt.getClaimAsString("email"),
            roles = jwt.realmRoles() + jwt.clientRoles(props.clientId)
        )
    }

    private fun Jwt.realmRoles(): Set<String> = rolesFrom(getClaimAsMap("realm_access"))

    private fun Jwt.clientRoles(clientId: String): Set<String> =
        rolesFrom(getClaimAsMap("resource_access")?.get(clientId) as? Map<*, *>)

    private fun rolesFrom(claim: Map<*, *>?): Set<String> =
        (claim?.get("roles") as? Collection<*>)
            ?.filterIsInstance<String>()
            ?.toSet()
            ?: emptySet()
}