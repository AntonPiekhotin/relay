package com.relay.user.service

import com.relay.common.dto.CreateUserRequest
import com.relay.common.dto.UserResponse
import com.relay.common.exception.RelayException
import com.relay.user.mapper.toEntity
import com.relay.user.mapper.toResponse
import com.relay.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(request: CreateUserRequest): UserResponse {
        if (userRepository.existsById(request.id)) {
            throw RelayException(HttpStatus.CONFLICT.value(), "User ${request.id} already exists")
        }
        if (userRepository.existsByEmail(request.email)) {
            throw RelayException(HttpStatus.CONFLICT.value(), "Email ${request.email} is already taken")
        }
        return try {
            // saveAndFlush so a constraint violation surfaces here rather than at commit.
            userRepository.saveAndFlush(request.toEntity()).toResponse()
                .also { logger.debug("Created profile for user {}", it.id) }
        } catch (ex: DataIntegrityViolationException) {
            throw RelayException(
                HttpStatus.CONFLICT.value(),
                "User ${request.id} already exists",
                ex
            )
        }
    }

    @Transactional(readOnly = true)
    fun getById(id: String): UserResponse =
        userRepository.findById(id)
            .orElseThrow { RelayException(HttpStatus.NOT_FOUND.value(), "User $id not found") }
            .toResponse()
}