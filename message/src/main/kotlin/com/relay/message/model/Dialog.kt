package com.relay.message.model

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Separates the two ids inside a [Dialog.directKey]. Rejected in an id, or the key would be ambiguous. */
const val DIRECT_KEY_SEPARATOR = ":"

enum class DialogType { DIRECT, GROUP }

/**
 * Membership lives here so message-service can resolve who receives a message. That resolution
 * has to be server-side: the fan-out list ends up on `messages.delivery` and drives what the
 * gateway pushes, so letting a client name its own recipients would let it push to anyone.
 *
 * Participants are eager because every send needs them.
 */
@Entity
@Table(name = "dialogs")
class Dialog(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    val type: DialogType = DialogType.DIRECT,

    /**
     * What makes "open the chat with Bob" idempotent: the pair decides the row, so two devices —
     * or both people at once — cannot end up with two conversations. Uniqueness is the database's
     * job (`uk_dialogs_direct_key`), never a check in the service.
     *
     * Null for a [DialogType.GROUP], which is not addressable by its membership.
     */
    @Column(name = "direct_key", length = 129)
    val directKey: String? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dialog_participants", joinColumns = [JoinColumn(name = "dialog_id")])
    @Column(name = "user_id", nullable = false, length = 64)
    val participantIds: MutableSet<String> = mutableSetOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
) {

    companion object {
        /** Sorted, so the caller and the callee of "open a chat" produce the same key. */
        fun directKeyOf(a: String, b: String): String =
            listOf(a, b).sorted().joinToString(DIRECT_KEY_SEPARATOR)
    }
}
