package com.relay.user.repository

import com.relay.user.model.UserAvatar
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserAvatarRepository : JpaRepository<UserAvatar, String>