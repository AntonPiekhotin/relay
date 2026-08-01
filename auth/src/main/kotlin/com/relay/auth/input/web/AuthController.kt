package com.relay.auth.input.web

import com.relay.auth.dto.ChangePasswordRequest
import com.relay.auth.dto.LoginRequest
import com.relay.auth.dto.RefreshRequest
import com.relay.auth.dto.RegisterRequest
import com.relay.auth.dto.TokenResponse
import com.relay.auth.service.AuthService
import com.relay.auth.util.userId
import com.relay.auth.util.username
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping(path = ["/api/v1/auth"])
class AuthController(
    private val authService: AuthService
) {
    @PostMapping("/register")
    fun register(@RequestBody req: RegisterRequest): Mono<ResponseEntity<TokenResponse>> =
        authService.register(req)
            .then(authService.login(LoginRequest(req.email, req.password)))
            .map { token -> ResponseEntity.status(HttpStatus.CREATED).body(token) }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): Mono<ResponseEntity<TokenResponse>> =
        authService.login(request)
            .map { ResponseEntity.ok(it) }

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshRequest): Mono<ResponseEntity<TokenResponse>> =
        authService.refresh(request)
            .map { ResponseEntity.ok(it) }

    @PostMapping("/logout")
    fun logout(@RequestBody request: RefreshRequest): Mono<Void> =
        authService.logout(request)

    /**
     * The one authenticated endpoint on this controller: who you are comes from the token, so the
     * body only carries the two passwords. 204 rather than a token pair — the caller's existing
     * tokens stay valid, so there is nothing to hand back.
     */
    @PostMapping("/password")
    fun changePassword(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: ChangePasswordRequest
    ): Mono<ResponseEntity<Void>> =
        authService.changePassword(jwt.userId(), jwt.username(), request)
            .then(Mono.fromCallable { ResponseEntity.noContent().build<Void>() })
}
