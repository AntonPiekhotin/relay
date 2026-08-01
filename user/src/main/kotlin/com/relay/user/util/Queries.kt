package com.relay.user.util

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable

/** A String because it is used as a `@RequestParam` default, which must be a compile-time constant. */
const val DEFAULT_PAGE_SIZE = "20"

const val MAX_PAGE_SIZE = 100

/** Shortest term a search accepts, so nobody pages through the whole user table one letter at a time. */
const val MIN_SEARCH_TERM_LENGTH = 2

/**
 * Page parameters are clamped rather than rejected: a client asking for 10 000 rows gets the
 * biggest page we are willing to serve, which is friendlier than a 400 and still bounds the query.
 */
fun pageRequestOf(page: Int, size: Int): Pageable =
    PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))

/**
 * Makes a user-supplied term safe as a SQL `LIKE` prefix. Without this, `%` or `_` in the term are
 * wildcards, so searching for `_` would match every user. Pairs with `escape '!'` in the query —
 * the escape character itself is doubled first, or escaping would be escapable.
 */
fun escapeLikeWildcards(term: String): String = term
    .replace("!", "!!")
    .replace("%", "!%")
    .replace("_", "!_")