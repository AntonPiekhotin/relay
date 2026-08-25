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

@RestController
@RequestMapping(path = ["/api/v1/auth"])
class AuthController(
    private val authService: AuthService
) {
    @PostMapping("/register")
    fun register(@RequestBody req: RegisterRequest): ResponseEntity<TokenResponse> {
        authService.register(req)
        val token = authService.login(LoginRequest(req.email, req.password))
        return ResponseEntity.status(HttpStatus.CREATED).body(token)
    }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.login(request))

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshRequest): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.refresh(request))

    @PostMapping("/logout")
    fun logout(@RequestBody request: RefreshRequest) {
        authService.logout(request)
    }

    @PostMapping("/password")
    fun changePassword(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: ChangePasswordRequest
    ): ResponseEntity<Void> {
        authService.changePassword(jwt.userId(), jwt.username(), request)
        return ResponseEntity.noContent().build()
    }
}