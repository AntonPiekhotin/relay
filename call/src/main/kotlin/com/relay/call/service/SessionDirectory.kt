package com.relay.call.service

interface SessionDirectory {

    fun onlineAmong(userIds: Collection<String>): Set<String>?
}
