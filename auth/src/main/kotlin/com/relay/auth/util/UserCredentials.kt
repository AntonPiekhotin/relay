package com.relay.auth.util

import org.keycloak.representations.idm.CredentialRepresentation


class UserCredentials {
    companion object {
        fun createPasswordCredentials(password: String): CredentialRepresentation {
            val passwordCredentials = CredentialRepresentation()
            passwordCredentials.isTemporary = false
            passwordCredentials.type = CredentialRepresentation.PASSWORD
            passwordCredentials.value = password
            return passwordCredentials
        }
    }
}
