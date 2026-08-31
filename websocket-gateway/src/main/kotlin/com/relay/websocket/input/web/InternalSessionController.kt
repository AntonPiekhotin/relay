package com.relay.websocket.input.web

import com.relay.websocket.session.SessionRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/internal/api/v1/sessions"])
class InternalSessionController(
    private val registry: SessionRegistry
) {

    @GetMapping("/online")
    fun online(@RequestParam("userId") userIds: Set<String>): Set<String> =
        userIds.filterTo(mutableSetOf(), registry::isOnline)
}
