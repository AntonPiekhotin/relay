package com.relay.user.repository

import com.relay.user.model.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<User, String> {

    fun existsByEmail(email: String): Boolean

    fun findByEmail(email: String): User?

    /**
     * Finding people to talk to. Two match rules on purpose:
     *  - email matches **exactly** — a prefix match on email would turn this endpoint into an
     *    address harvester ("a", "b", "c" … enumerates the user table).
     *  - names match by prefix, which is what a type-ahead needs.
     *
     * [prefix] arrives already escaped by the caller and pairs with `escape '!'`, so a query
     * containing `%` or `_` is matched literally instead of acting as a wildcard.
     *
     * The `order by` is there to make paging deterministic; without a total order the same row can
     * appear on two pages. `id` is the tiebreaker because names are not unique.
     */
    @Query(
        value = """
            select u from User u
            where u.id <> :selfId
              and (lower(u.email) = lower(:term)
                or lower(u.firstName) like lower(concat(:prefix, '%')) escape '!'
                or lower(u.lastName) like lower(concat(:prefix, '%')) escape '!')
            order by lower(u.firstName), lower(u.lastName), u.id
        """,
        countQuery = """
            select count(u) from User u
            where u.id <> :selfId
              and (lower(u.email) = lower(:term)
                or lower(u.firstName) like lower(concat(:prefix, '%')) escape '!'
                or lower(u.lastName) like lower(concat(:prefix, '%')) escape '!')
        """
    )
    fun search(
        @Param("selfId") selfId: String,
        @Param("term") term: String,
        @Param("prefix") prefix: String,
        pageable: Pageable
    ): Page<User>
}