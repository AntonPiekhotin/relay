package com.relay.user.service

import com.relay.common.dto.CreateUserRequest
import com.relay.common.dto.UserResponse
import com.relay.common.exception.RelayException
import com.relay.user.mapper.toEntity
import com.relay.user.mapper.toProfile
import com.relay.user.mapper.toResponse
import com.relay.user.mapper.toSummary
import com.relay.user.model.User
import com.relay.user.model.dto.PagedResponse
import com.relay.user.model.dto.ProfileResponse
import com.relay.user.model.dto.UpdateProfileRequest
import com.relay.user.model.dto.UserSearchResultResponse
import com.relay.user.model.dto.UserSummaryResponse
import com.relay.user.repository.ContactRepository
import com.relay.user.repository.UserRepository
import com.relay.user.util.MIN_SEARCH_TERM_LENGTH
import com.relay.user.util.escapeLikeWildcards
import com.relay.user.util.pageRequestOf
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val contactRepository: ContactRepository
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
    fun getById(id: String): UserResponse = requireUser(id).toResponse()

    /** The caller's own profile, including fields nobody else is shown. */
    @Transactional(readOnly = true)
    fun getProfile(id: String): ProfileResponse = requireUser(id).toProfile()

    /** Somebody else's profile: the public subset, so a lookup by id cannot mine private fields. */
    @Transactional(readOnly = true)
    fun getSummary(id: String): UserSummaryResponse = requireUser(id).toSummary()

    /**
     * Replaces the editable projection of the profile — both names, every time. A client that sends
     * back a stale value therefore overwrites a change another of its devices made in between; the
     * window is two fields wide and accepted as the price of an unambiguous `PUT`.
     *
     * Blank names are refused by `@NotBlank` on the request, not here. Trimming stays, because Bean
     * Validation rejects values without transforming them: `@NotBlank` refuses `"   "` but lets
     * `" Alicia "` through, and a stored leading space quietly reorders the alphabetical contact list.
     */
    @Transactional
    fun updateProfile(id: String, request: UpdateProfileRequest): ProfileResponse {
        val user = requireUser(id)
        user.firstName = request.firstName.trim()
        user.lastName = request.lastName.trim()
        user.updatedAt = Instant.now()
        logger.debug("Updated profile for user {}", id)
        return user.toProfile()
    }

    /**
     * Finding people to start a dialog with. The caller is excluded — you cannot add yourself — and
     * every hit carries whether they are already in the caller's contacts, so the client can render
     * "Add" or "Added" without a second round trip per row.
     */
    @Transactional(readOnly = true)
    fun search(selfId: String, query: String, page: Int, size: Int): PagedResponse<UserSearchResultResponse> {
        val term = query.trim()
        if (term.length < MIN_SEARCH_TERM_LENGTH) {
            throw RelayException(
                HttpStatus.BAD_REQUEST.value(),
                "Search query must be at least $MIN_SEARCH_TERM_LENGTH characters"
            )
        }
        val found = userRepository.search(selfId, term, escapeLikeWildcards(term), pageRequestOf(page, size))
        val contactIds = contactUserIdsAmong(selfId, found.content.map(User::id))
        return PagedResponse.of(found) { UserSearchResultResponse(it.toSummary(), it.id in contactIds) }
    }

    /** Guarded against an empty list: a derived `in ()` query is a syntax error on most databases. */
    private fun contactUserIdsAmong(ownerId: String, userIds: List<String>): Set<String> =
        if (userIds.isEmpty()) emptySet()
        else contactRepository.findAllByOwnerIdAndContactUserIdIn(ownerId, userIds)
            .mapTo(mutableSetOf()) { it.contactUserId }

    /** Managed inside the caller's transaction, so mutations to it are persisted by dirty checking. */
    private fun requireUser(id: String): User =
        userRepository.findById(id)
            .orElseThrow { RelayException(HttpStatus.NOT_FOUND.value(), "User $id not found") }
}