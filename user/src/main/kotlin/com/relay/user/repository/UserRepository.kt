package com.relay.user.repository

import com.relay.user.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<User, String> {

    fun existsByEmail(email: String): Boolean

    fun findByEmail(email: String): User?
}