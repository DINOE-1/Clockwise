package com.dinoe.clockwise.data

/**
 * Scope of an edit or delete on an event series. Kept in the data layer so the schedule writer
 * and the AI tools share the exact same semantics as the calendar UI.
 */
enum class EventScope {
    THIS_EVENT,
    THIS_AND_SUBSEQUENT,
    ENTIRE_SERIES,
}
