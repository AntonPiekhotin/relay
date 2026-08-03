package com.relay.user.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * `users` rather than `user` because `user` is a reserved word in Postgres.
 *
 * [id] is not generated: it is the Keycloak user id supplied by auth, so the profile
 * shares its primary key with the identity and with the `sub` claim of every token.
 *
 * [email] is deliberately NOT mutable through the profile API. It doubles as the Keycloak
 * username, so changing it here alone would desync identity from profile; an email change has
 * to start in auth.
 *
 * [avatarUrl] is denormalized on purpose: it is derived from the `user_avatars`
 * row, but storing it means listing contacts or search results never has to join the blob
 * table just to answer "does this user have a picture".
 */
@Entity
@Table(
    name = "users",
    indexes = [Index(name = "idx_users_last_name", columnList = "last_name")]
)
class User(

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 64)
    val id: String,

    @Column(name = "email", nullable = false, unique = true, length = 256)
    var email: String,

    @Column(name = "first_name", nullable = false, length = 128)
    var firstName: String,

    @Column(name = "last_name", nullable = false, length = 128)
    var lastName: String,

    @Column(name = "avatar_url", length = 512)
    var avatarUrl: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)