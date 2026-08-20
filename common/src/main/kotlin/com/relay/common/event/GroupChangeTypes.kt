package com.relay.common.event

/**
 * The vocabulary of [MessageDeliveryEvent.GroupChanged.change]. Plain strings rather than an enum
 * for the same reason every cross-service vocabulary here is: an unknown value must be skippable
 * by an older consumer, not a deserialization failure.
 */
object GroupChangeTypes {
    const val GROUP_CREATED = "GROUP_CREATED"
    const val MEMBER_ADDED = "MEMBER_ADDED"
    const val MEMBER_REMOVED = "MEMBER_REMOVED"
    const val MEMBER_LEFT = "MEMBER_LEFT"
    const val GROUP_RENAMED = "GROUP_RENAMED"
    const val GROUP_DELETED = "GROUP_DELETED"
}
