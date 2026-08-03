package com.relay.user.input.web

import com.relay.user.model.dto.AvatarResponse
import com.relay.user.model.dto.PagedResponse
import com.relay.user.model.dto.ProfileResponse
import com.relay.user.model.dto.UpdateProfileRequest
import com.relay.user.model.dto.UserSearchResultResponse
import com.relay.user.model.dto.UserSummaryResponse
import com.relay.user.service.AvatarService
import com.relay.user.service.UserService
import com.relay.user.util.DEFAULT_PAGE_SIZE
import com.relay.user.util.userId
import jakarta.validation.Valid
import java.time.Duration
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Client-facing profile surface, reached through the api-gateway's `/api/v1/user` route. The JWT
 * is validated here too, not only at the gateway, and every "my" endpoint
 * resolves the subject from the token rather than the path.
 *
 * Password is not here: credentials live in Keycloak, so changing one is `POST /api/v1/auth/password`
 * on the auth service.
 */
@RestController
@RequestMapping("/api/v1/user")
class UserController(
    private val userService: UserService,
    private val avatarService: AvatarService
) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<ProfileResponse> =
        ResponseEntity.ok(userService.getProfile(jwt.userId()))

    /**
     * `PUT`, so the body is the complete editable projection of the profile — both names, replaced
     * as one. It is deliberately narrower than what `GET /me` returns: `email`, `avatarUrl` and the
     * timestamps are not client-owned, and each has its own endpoint or none at all.
     */
    @PutMapping("/me")
    fun updateMe(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<ProfileResponse> =
        ResponseEntity.ok(userService.updateProfile(jwt.userId(), request))

    /** Find people to talk to. Literal path, so it is matched before `/{id}`. */
    @GetMapping("/search")
    fun search(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam query: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) size: Int
    ): ResponseEntity<PagedResponse<UserSearchResultResponse>> =
        ResponseEntity.ok(userService.search(jwt.userId(), query, page, size))

    /** The public subset of another user's profile — for rendering a dialog header, say. */
    @GetMapping("/{id}")
    fun byId(@PathVariable id: String): ResponseEntity<UserSummaryResponse> =
        ResponseEntity.ok(userService.getSummary(id))

    @PostMapping("/me/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAvatar(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestPart("file") file: MultipartFile
    ): ResponseEntity<AvatarResponse> =
        ResponseEntity.ok(avatarService.upload(jwt.userId(), file))

    @DeleteMapping("/me/avatar")
    fun deleteAvatar(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        avatarService.delete(jwt.userId())
        return ResponseEntity.noContent().build()
    }

    /**
     * Serves the stored bytes. The content type is the one detected from the file itself
     * ([AvatarService]), so `nosniff` and this header agree and a browser cannot be talked into
     * interpreting a picture as a document. Caching is aggressive but safe: the URL in the profile
     * carries a `v` stamp that changes whenever the picture does.
     */
    @GetMapping("/{id}/avatar")
    fun avatar(@PathVariable id: String): ResponseEntity<ByteArray> {
        val avatar = avatarService.load(id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(avatar.contentType))
            .contentLength(avatar.sizeBytes.toLong())
            .eTag("\"${avatar.updatedAt.toEpochMilli()}\"")
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .header("X-Content-Type-Options", "nosniff")
            .body(avatar.bytes)
    }
}