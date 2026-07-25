package com.relay.auth.service

import com.relay.auth.dto.LoginRequest
import com.relay.auth.dto.RefreshRequest
import com.relay.auth.dto.RegisterRequest
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class AuthService(
    private val keycloakService: KeycloakService
) {

    fun login(request: LoginRequest) =
        keycloakService.login(request)

    fun refresh(request: RefreshRequest) =
        keycloakService.refresh(request.refreshToken)

    fun logout(request: RefreshRequest) =
        keycloakService.logout(request.refreshToken)

    fun register(request: RegisterRequest): Mono<Void> =
        Mono.fromRunnable { keycloakService.registerUser(request) }
}
