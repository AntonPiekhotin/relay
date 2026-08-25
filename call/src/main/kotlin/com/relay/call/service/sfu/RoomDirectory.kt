package com.relay.call.service.sfu

interface RoomDirectory {

    fun participantIdentities(room: String): Set<String>?

    fun closeRoom(room: String)
}
