package com.relay.common.event

object CallSignalVerbs {
    const val INVITE = "invite"
    const val ACCEPT = "accept"
    const val REJECT = "reject"
    const val ICE = "ice"
    const val HANGUP = "hangup"

    /**
     * Stop showing this call — sent to a user's *other* devices once one of them settled it, so a
     * phone stops ringing after the call was answered on a tablet.
     */
    const val CANCEL = "cancel"
    const val MISSED = "missed"

    /** Progress a client could not have inferred; currently only "your invite is ringing". */
    const val STATE = "state"
    const val GROUP_INVITE = "group_invite"
    const val PARTICIPANT_JOINED = "participant_joined"
    const val PARTICIPANT_LEFT = "participant_left"
    const val PARTICIPANT_DECLINED = "participant_declined"
    const val PARTICIPANT_MISSED = "participant_missed"
    const val GROUP_ENDED = "group_ended"
}

object CallSignalKeys {
    const val VERB = "verb"
    const val MEDIA = "media"
    const val SDP = "sdp"
    const val CANDIDATE = "candidate"
    const val REASON = "reason"
    const val STATUS = "status"
    const val DIALOG_ID = "dialog_id"
    const val STARTED_AT = "started_at"
    const val DURATION_S = "duration_s"
    const val RING_EXPIRES_AT = "ring_expires_at"
    const val KIND = "kind"
    const val USER_ID = "user_id"
    const val PARTICIPANTS = "participants"

    /** A participant's state inside a [PARTICIPANTS] entry. */
    const val STATE = "state"
}
