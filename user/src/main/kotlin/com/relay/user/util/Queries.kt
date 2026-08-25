package com.relay.user.util

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable

const val DEFAULT_PAGE_SIZE = "20"

const val MAX_PAGE_SIZE = 100

const val MIN_SEARCH_TERM_LENGTH = 2

fun pageRequestOf(page: Int, size: Int): Pageable =
    PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))

fun escapeLikeWildcards(term: String): String = term
    .replace("!", "!!")
    .replace("%", "!%")
    .replace("_", "!_")