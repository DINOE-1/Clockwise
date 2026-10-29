package com.dinoe.clockwise.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object AiTools {
    const val LIST_ALARMS = "list_alarms"
    const val LIST_EVENTS = "list_events"
    const val CREATE_ALARMS = "create_alarms"
    const val CREATE_ALARM_STACKS = "create_alarm_stacks"
    const val CREATE_EVENTS = "create_events"
    const val UPDATE_ALARMS = "update_alarms"
    const val DELETE_ALARMS = "delete_alarms"
    const val UPDATE_EVENTS = "update_events"
    const val DELETE_EVENTS = "delete_events"

    val writeTools: Set<String> = setOf(
        CREATE_ALARMS,
        CREATE_ALARM_STACKS,
        CREATE_EVENTS,
        UPDATE_ALARMS,
        DELETE_ALARMS,
        UPDATE_EVENTS,
        DELETE_EVENTS,
    )
    val readTools: Set<String> = setOf(LIST_ALARMS, LIST_EVENTS)

    val definitions: JsonArray by lazy {
        buildJsonArray {
            add(
                functionTool(
                    name = LIST_ALARMS,
                    description = "List the user's existing alarms. Use only when the user asks " +
                        "about current alarms or duplicates are possible.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                        put("additionalProperties", false)
                    },
                ),
            )
            add(
                functionTool(
                    name = LIST_EVENTS,
                    description = "List calendar events in a date range. Defaults to the next 7 " +
                        "days; maximum 366 days and 30 events returned.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("from") {
                                put("type", "string")
                                put("description", "Optional ISO date YYYY-MM-DD")
                            }
                            putJsonObject("to") {
                                put("type", "string")
                                put("description", "Optional ISO date YYYY-MM-DD")
                            }
                        }
                        put("additionalProperties", false)
                    },
                ),
            )
            add(
                functionTool(
                    name = CREATE_ALARMS,
                    description = "Propose creating alarms. Omit date for the next occurrence " +
                        "of time; include date YYYY-MM-DD for a specific day. oneTime deletes " +
                        "the alarm after it rings; enabled=false creates it switched off. For " +
                        "repeating reminders use create_events with withAlarm. The user must " +
                        "confirm the preview.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("alarms") {
                                put("type", "array")
                                put("description", "All alarms to create, up to 30")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("time") {
                                            put("type", "string")
                                            put("description", "Local 24-hour time HH:mm")
                                        }
                                        putJsonObject("date") {
                                            put("type", "string")
                                            put(
                                                "description",
                                                "Optional ISO date YYYY-MM-DD; omit for the next occurrence",
                                            )
                                        }
                                        putJsonObject("name") {
                                            put("type", "string")
                                            put("description", "Short display name")
                                        }
                                        putJsonObject("note") { put("type", "string") }
                                        putJsonObject("enabled") {
                                            put("type", "boolean")
                                            put(
                                                "description",
                                                "Defaults to true; false creates a disabled alarm",
                                            )
                                        }
                                        putJsonObject("oneTime") {
                                            put("type", "boolean")
                                            put(
                                                "description",
                                                "Delete the alarm after it rings; default false",
                                            )
                                        }
                                        putJsonObject("tags") {
                                            put("type", "array")
                                            put("description", "Existing tag names only")
                                            putJsonObject("items") { put("type", "string") }
                                        }
                                    }
                                    putJsonArray("required") { add("time") }
                                    put("additionalProperties", false)
                                }
                            }
                        }
                        putJsonArray("required") { add("alarms") }
                        put("additionalProperties", false)
                    },
                ),
            )
            add(
                functionTool(
                    name = CREATE_ALARM_STACKS,
                    description = "Propose creating a stack of related plain alarms that share " +
                        "one name, note, tags and oneTime setting but ring at different times. " +
                        "Use this when the user asks for several alarms together, for example " +
                        "7:00, 7:10 and 7:20; they appear as one expandable stack. Each stack " +
                        "needs at least two different HH:mm times and cannot have a date. Use " +
                        "create_alarms for independent alarms. The user must confirm the preview.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("stacks") {
                                put("type", "array")
                                put("description", "All alarm stacks to create, up to 30")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("times") {
                                            put("type", "array")
                                            put(
                                                "description",
                                                "At least two different local times HH:mm",
                                            )
                                            putJsonObject("items") { put("type", "string") }
                                        }
                                        putJsonObject("name") { put("type", "string") }
                                        putJsonObject("note") { put("type", "string") }
                                        putJsonObject("enabled") {
                                            put("type", "boolean")
                                            put(
                                                "description",
                                                "Defaults to true; false creates disabled alarms",
                                            )
                                        }
                                        putJsonObject("oneTime") {
                                            put("type", "boolean")
                                            put(
                                                "description",
                                                "Delete each alarm after it rings; default false",
                                            )
                                        }
                                        putJsonObject("tags") {
                                            put("type", "array")
                                            put("description", "Existing tag names only")
                                            putJsonObject("items") { put("type", "string") }
                                        }
                                    }
                                    putJsonArray("required") { add("times") }
                                    put("additionalProperties", false)
                                }
                            }
                        }
                        putJsonArray("required") { add("stacks") }
                        put("additionalProperties", false)
                    },
                ),
            )
            add(
                functionTool(
                    name = CREATE_EVENTS,
                    description = "Propose creating calendar events. Recurrence none/daily/weekly; " +
                        "daily and weekly require until. withAlarm creates an alarm at each start. " +
                        "reminderMinutes 5/10/30 shows a notification before each occurrence; 0 " +
                        "or omitted disables it. Omit end for a quick activity that only needs a " +
                        "start time. Set allDay=true for an all-day event. For an event that ends " +
                        "on a later day (for example 23:00 to 07:00), pass endDate with the end " +
                        "day; end may then be earlier than start.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("events") {
                                put("type", "array")
                                put("description", "All events to create, up to 30")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("title") { put("type", "string") }
                                        putJsonObject("note") { put("type", "string") }
                                        putJsonObject("date") { put("type", "string") }
                                        putJsonObject("start") {
                                            put("type", "string")
                                            put(
                                                "description",
                                                "HH:mm; ignored when allDay is true",
                                            )
                                        }
                                        putJsonObject("end") {
                                            put("type", "string")
                                            put(
                                                "description",
                                                "HH:mm; omit for an event with only a start " +
                                                    "time; ignored when allDay is true",
                                            )
                                        }
                                        putJsonObject("endDate") {
                                            put("type", "string")
                                            put(
                                                "description",
                                                "Optional YYYY-MM-DD end day; use for events " +
                                                    "that finish on a later day",
                                            )
                                        }
                                        putJsonObject("allDay") {
                                            put("type", "boolean")
                                            put(
                                                "description",
                                                "All-day event with no time; defaults to false",
                                            )
                                        }
                                        putJsonObject("withAlarm") { put("type", "boolean") }
                                        putJsonObject("reminderMinutes") {
                                            put("type", "integer")
                                            putJsonArray("enum") {
                                                add(0)
                                                add(5)
                                                add(10)
                                                add(30)
                                            }
                                            put(
                                                "description",
                                                "Notification minutes before the start; 0 disables",
                                            )
                                        }
                                        putJsonObject("repeat") {
                                            put("type", "object")
                                            putJsonObject("properties") {
                                                putJsonObject("type") {
                                                    put("type", "string")
                                                    putJsonArray("enum") {
                                                        add("none")
                                                        add("daily")
                                                        add("weekly")
                                                    }
                                                }
                                                putJsonObject("days") {
                                                    put("type", "array")
                                                    put(
                                                        "description",
                                                        "ISO weekdays 1=Mon..7=Sun",
                                                    )
                                                    putJsonObject("items") {
                                                        put("type", "integer")
                                                    }
                                                }
                                                putJsonObject("until") { put("type", "string") }
                                            }
                                            putJsonArray("required") { add("type") }
                                        }
                                        putJsonObject("tags") {
                                            put("type", "array")
                                            put("description", "Existing tag names only")
                                            putJsonObject("items") { put("type", "string") }
                                        }
                                    }
                                    putJsonArray("required") {
                                        add("title")
                                        add("date")
                                        add("start")
                                    }
                                    put("additionalProperties", false)
                                }
                            }
                        }
                        putJsonArray("required") { add("events") }
                        put("additionalProperties", false)
                    },
                ),
            )
            add(
                functionTool(
                    name = UPDATE_ALARMS,
                    description = "Update existing plain alarms. Call list_alarms first for ids. " +
                        "Omitted fields keep their value; tags replace the full list; " +
                        "clearDate removes a date; oneTime switches delete-after-ring. " +
                        "Event-linked alarms can only toggle enabled.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("alarms") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("id") { put("type", "integer") }
                                        putJsonObject("time") {
                                            put("type", "string")
                                            put("description", "HH:mm")
                                        }
                                        putJsonObject("date") {
                                            put("type", "string")
                                            put("description", "YYYY-MM-DD")
                                        }
                                        putJsonObject("clearDate") {
                                            put("type", "boolean")
                                            put(
                                                "description",
                                                "Remove the date and make it a plain alarm",
                                            )
                                        }
                                        putJsonObject("name") { put("type", "string") }
                                        putJsonObject("note") { put("type", "string") }
                                        putJsonObject("enabled") { put("type", "boolean") }
                                        putJsonObject("oneTime") {
                                            put("type", "boolean")
                                            put("description", "Delete the alarm after it rings")
                                        }
                                        putJsonObject("tags") {
                                            put("type", "array")
                                            putJsonObject("items") { put("type", "string") }
                                        }
                                    }
                                    putJsonArray("required") { add("id") }
                                    put("additionalProperties", false)
                                }
                            }
                        }
                        putJsonArray("required") { add("alarms") }
                        put("additionalProperties", false)
                    },
                ),
            )
            add(
                functionTool(
                    name = DELETE_ALARMS,
                    description = "Delete alarms by id. Call list_alarms first. Deleting an " +
                        "event-linked alarm leaves the event in place but it will not ring.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("alarmIds") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "integer") }
                            }
                        }
                        putJsonArray("required") { add("alarmIds") }
                        put("additionalProperties", false)
                    },
                ),
            )
            add(
                functionTool(
                    name = UPDATE_EVENTS,
                    description = "Update existing events. Call list_events first for ids. " +
                        "Omitted fields keep their value; tags replace the full list; " +
                        "reminderMinutes 0/5/10/30 changes the notification reminder; repeat " +
                        "rules cannot be changed. Date cannot change with entire_series. Use " +
                        "allDay=true for an all-day event and endDate when the event ends on a " +
                        "later day.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("events") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("id") { put("type", "integer") }
                                        putJsonObject("scope") {
                                            put("type", "string")
                                            putJsonArray("enum") {
                                                add("this")
                                                add("this_and_subsequent")
                                                add("entire_series")
                                            }
                                        }
                                        putJsonObject("title") { put("type", "string") }
                                        putJsonObject("note") { put("type", "string") }
                                        putJsonObject("date") {
                                            put("type", "string")
                                            put("description", "YYYY-MM-DD")
                                        }
                                        putJsonObject("start") {
                                            put("type", "string")
                                            put("description", "HH:mm")
                                        }
                                        putJsonObject("end") {
                                            put("type", "string")
                                            put(
                                                "description",
                                                "HH:mm; equal to start removes the end time",
                                            )
                                        }
                                        putJsonObject("endDate") {
                                            put("type", "string")
                                            put(
                                                "description",
                                                "YYYY-MM-DD; use when the event ends on a " +
                                                    "later day",
                                            )
                                        }
                                        putJsonObject("allDay") {
                                            put("type", "boolean")
                                            put(
                                                "description",
                                                "All-day event with no time; false restores " +
                                                    "timed events",
                                            )
                                        }
                                        putJsonObject("withAlarm") { put("type", "boolean") }
                                        putJsonObject("reminderMinutes") {
                                            put("type", "integer")
                                            putJsonArray("enum") {
                                                add(0)
                                                add(5)
                                                add(10)
                                                add(30)
                                            }
                                            put(
                                                "description",
                                                "Notification minutes before the start; 0 disables",
                                            )
                                        }
                                        putJsonObject("tags") {
                                            put("type", "array")
                                            putJsonObject("items") { put("type", "string") }
                                        }
                                    }
                                    putJsonArray("required") { add("id") }
                                    put("additionalProperties", false)
                                }
                            }
                        }
                        putJsonArray("required") { add("events") }
                        put("additionalProperties", false)
                    },
                ),
            )
            add(
                functionTool(
                    name = DELETE_EVENTS,
                    description = "Delete events by id. Call list_events first. Scope selects " +
                        "this event, this and subsequent, or the whole series. keepAlarms " +
                        "defaults to false, so linked alarms are deleted too.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("events") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("id") { put("type", "integer") }
                                        putJsonObject("scope") {
                                            put("type", "string")
                                            putJsonArray("enum") {
                                                add("this")
                                                add("this_and_subsequent")
                                                add("entire_series")
                                            }
                                        }
                                        putJsonObject("keepAlarms") { put("type", "boolean") }
                                    }
                                    putJsonArray("required") { add("id") }
                                    put("additionalProperties", false)
                                }
                            }
                        }
                        putJsonArray("required") { add("events") }
                        put("additionalProperties", false)
                    },
                ),
            )
        }
    }

    private fun functionTool(
        name: String,
        description: String,
        parameters: JsonObject,
    ): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        }
    }
}
