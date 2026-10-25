package com.dinoe.clockwise.data

/**
 * Android allows roughly 500 exact alarms per application. Keep a safety margin because the
 * limit is enforced by the platform across all scheduler APIs and OEMs may count differently.
 */
const val MAX_ACTIVE_ALARMS = 400

/**
 * Upper bound for one recurring series. This is intentionally lower than the platform alarm
 * limit so a handful of recurring events cannot consume the whole alarm budget.
 */
const val MAX_OCCURRENCES_PER_SERIES = 180
