package com.relay.user.model.dto

import org.springframework.data.domain.Page

/**
 * Our own page envelope rather than serializing Spring's [Page]: `PageImpl`'s JSON shape is not
 * a stable contract (Boot logs a warning when you return one) and carries `pageable`/`sort`
 * internals a client has no use for.
 */
data class PagedResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean
) {
    companion object {

        fun <E : Any, T> of(page: Page<E>, transform: (E) -> T) = PagedResponse(
            items = page.content.map(transform),
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            hasNext = page.hasNext()
        )
    }
}