package com.relay.auth.input.web

import com.relay.auth.dto.LoginRequest
import com.relay.auth.dto.RefreshRequest
import com.relay.auth.dto.RegisterRequest
import com.relay.auth.dto.TokenResponse
import com.relay.auth.service.AuthService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
}
