package com.relay.message.service

import com.relay.message.dto.ChatResponse
import com.relay.message.dto.CreateChatRequest
import com.relay.message.mapper.toResponse
import com.relay.message.model.Chat
import com.relay.message.repository.ChatRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChatService(
    private val chatRepository: ChatRepository
) {

    @Transactional
    fun create(request: CreateChatRequest): ChatResponse =
        chatRepository.save(Chat(participantIds = request.participantIds.toMutableSet())).toResponse()
}