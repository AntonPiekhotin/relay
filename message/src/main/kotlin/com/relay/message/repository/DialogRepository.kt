package com.relay.message.repository

import com.relay.message.model.Dialog
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DialogRepository : JpaRepository<Dialog, UUID>