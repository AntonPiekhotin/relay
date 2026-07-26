package com.relay.user.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * `users` rather than `user` because `user` is a reserved word in Postgres.
 *
 * [id] is not generated: it is the Keycloak user id supplied by auth, so the profile
 * shares its primary key with the identity and with the `sub` claim of every token.
 */
@Entity
@Table(name = "users")
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

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)