package com.relay.message.service

import com.relay.message.dto.CreateDialogRequest
import com.relay.message.dto.DialogResponse
import com.relay.message.mapper.toResponse
import com.relay.message.model.Dialog
import com.relay.message.repository.DialogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DialogService(
    private val dialogRepository: DialogRepository
) {

    @Transactional
    fun create(request: CreateDialogRequest): DialogResponse =
        dialogRepository.save(Dialog(participantIds = request.participantIds.toMutableSet())).toResponse()
}