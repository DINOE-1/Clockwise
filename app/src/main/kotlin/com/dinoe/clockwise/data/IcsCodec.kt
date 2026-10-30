package com.dinoe.clockwise.data

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class IcsException(message: String) : Exception(message)

/** One parsed VEVENT, already reduced to the fields `ScheduleWriter.addEvent` accepts. */
internal data class IcsEventDraft(
    val title: String,
    val note: String,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val endDayOffset: Int,
    val allDay: Boolean,
    val repeat: RecurrenceRule,
    val reminderMinutes: Int,
)

/** Upper bound on VEVENTs accepted from one file, matching the JSON backup event limit. */
internal const val MAX_ICS_EVENTS = 10_000

private const val WRITE_BUFFER_CHARS = 8 * 1024
private const val MAX_LINE_OCTETS = 75
private val DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)
private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
    .withZone(ZoneOffset.UTC)

/**
 * Writes the calendar as one VEVENT per event, collapsing a recurring series into a single VEVENT
 * with an RRULE when its rows still match the generated recurrence. A row that was edited or
 * deleted individually breaks that match and is exported on its own so the file keeps every row.
 *
 * Timed values are floating local times: Clockwise stores wall-clock times without a zone, so a
 * floating value preserves exactly what the user sees.
 */
internal fun writeIcs(
    output: OutputStream,
    events: List<Event>,
    timestamp: Instant = Instant.now(),
) {
    val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), WRITE_BUFFER_CHARS)
    writeLine(writer, "BEGIN:VCALENDAR")
    writeLine(writer, "VERSION:2.0")
    writeLine(writer, "PRODID:-//Clockwise//Clockwise//EN")
    writeLine(writer, "CALSCALE:GREGORIAN")
    planExportUnits(events).forEach { unit -> writeEvent(writer, unit, timestamp) }
    writeLine(writer, "END:VCALENDAR")
    writer.flush()
}

private data class ExportUnit(
    val anchor: Event,
    val repeat: ExportRepeat?,
)

private data class ExportRepeat(
    val type: Int,
    val days: Set<Int>,
    val until: LocalDate,
)

private fun planExportUnits(events: List<Event>): List<ExportUnit> {
    val units = mutableListOf<ExportUnit>()
    val bySeries = mutableMapOf<Long, MutableList<Event>>()
    for (event in events) {
        if (event.seriesId == 0L) {
            units += ExportUnit(event, null)
        } else {
            bySeries.getOrPut(event.seriesId) { mutableListOf() } += event
        }
    }
    for (rows in bySeries.values) {
        val sorted = rows.sortedBy { it.occurrenceIndex }
        val repeat = collapseRepeat(sorted)
        if (repeat == null) {
            sorted.forEach { units += ExportUnit(it, null) }
        } else {
            units += ExportUnit(sorted.first(), repeat)
        }
    }
    return units.sortedWith(
        compareBy({ it.anchor.date }, { it.anchor.start }, { it.anchor.id }),
    )
}

/**
 * Returns the RRULE that reproduces every row of [rows], or null when the rows no longer follow
 * the recurrence plan (an occurrence was edited or removed).
 */
private fun collapseRepeat(rows: List<Event>): ExportRepeat? {
    if (rows.size < 2) return null
    val base = rows.first()
    if (base.repeatType == Event.REPEAT_NONE) return null
    val sameSharedFields = rows.all { row ->
        row.repeatType == base.repeatType &&
            row.repeatDays == base.repeatDays &&
            row.allDay == base.allDay &&
            row.title == base.title &&
            row.note == base.note &&
            row.start == base.start &&
            row.end == base.end &&
            row.reminderMinutes == base.reminderMinutes
    }
    if (!sameSharedFields) return null
    if (rows.map { it.occurrenceIndex } != (0 until rows.size).toList()) return null

    val days = parseRepeatDays(base.repeatDays)
    if (base.repeatType == Event.REPEAT_WEEKLY && days.isEmpty()) return null
    val untilEpochDay = rows.last().date
    if (untilEpochDay <= base.date) return null
    val plan = planRecurrence(
        LocalDate.ofEpochDay(base.date),
        RecurrenceRule(base.repeatType, days, untilEpochDay),
    )
    if (plan.limitReached) return null
    val actual = rows.drop(1).map { it.date }
    if (plan.dates.map { it.toEpochDay() } != actual) return null
    return ExportRepeat(base.repeatType, days, LocalDate.ofEpochDay(untilEpochDay))
}

private fun writeEvent(writer: Writer, unit: ExportUnit, timestamp: Instant) {
    val event = unit.anchor
    val date = LocalDate.ofEpochDay(event.date)
    writeLine(writer, "BEGIN:VEVENT")
    writeLine(writer, "UID:${eventUid(unit)}")
    writeLine(writer, "DTSTAMP:${UTC_FORMAT.format(timestamp)}")
    if (event.allDay) {
        writeLine(writer, "DTSTART;VALUE=DATE:${DATE_FORMAT.format(date)}")
        writeLine(writer, "DTEND;VALUE=DATE:${DATE_FORMAT.format(date.plusDays(1))}")
    } else {
        val start = date.atStartOfDay().plusSeconds(event.start.toLong())
        val end = date.atStartOfDay().plusSeconds(event.end.toLong())
        writeLine(writer, "DTSTART:${DATE_TIME_FORMAT.format(start)}")
        writeLine(writer, "DTEND:${DATE_TIME_FORMAT.format(if (end.isAfter(start)) end else start)}")
    }
    writeLine(writer, "SUMMARY:${escapeText(event.title)}")
    if (event.note.isNotBlank()) writeLine(writer, "DESCRIPTION:${escapeText(event.note)}")
    unit.repeat?.let { repeat ->
        val rule = StringBuilder("RRULE:FREQ=")
        rule.append(if (repeat.type == Event.REPEAT_DAILY) "DAILY" else "WEEKLY")
        if (repeat.type == Event.REPEAT_WEEKLY && repeat.days.isNotEmpty()) {
            rule.append(";BYDAY=")
            rule.append(repeat.days.sorted().joinToString(",") { isoDayName(it) })
        }
        rule.append(";UNTIL=").append(DATE_FORMAT.format(repeat.until))
        writeLine(writer, rule.toString())
    }
    if (!event.allDay && event.reminderMinutes > 0) {
        writeLine(writer, "BEGIN:VALARM")
        writeLine(writer, "ACTION:DISPLAY")
        writeLine(writer, "DESCRIPTION:${escapeText(event.title)}")
        writeLine(writer, "TRIGGER:-PT${event.reminderMinutes}M")
        writeLine(writer, "END:VALARM")
    }
    writeLine(writer, "END:VEVENT")
}

private fun eventUid(unit: ExportUnit): String =
    if (unit.repeat != null) {
        "clockwise-series-${unit.anchor.seriesId}@clockwise.app"
    } else {
        "clockwise-event-${unit.anchor.id}@clockwise.app"
    }

private fun parseRepeatDays(value: String): Set<Int> =
    value.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()

private fun isoDayName(isoDay: Int): String = when (isoDay) {
    1 -> "MO"
    2 -> "TU"
    3 -> "WE"
    4 -> "TH"
    5 -> "FR"
    6 -> "SA"
    else -> "SU"
}

private fun escapeText(value: String): String {
    val escaped = StringBuilder(value.length)
    for (character in value) {
        when (character) {
            '\\' -> escaped.append("\\\\")
            ';' -> escaped.append("\\;")
            ',' -> escaped.append("\\,")
            '\n' -> escaped.append("\\n")
            '\r' -> Unit
            else -> escaped.append(character)
        }
    }
    return escaped.toString()
}

/** Folds one content line at 75 UTF-8 octets as RFC 5545 requires. */
private fun writeLine(writer: Writer, line: String) {
    val buffer = StringBuilder()
    var bytesUsed = 0
    var continuation = false
    var index = 0
    while (index < line.length) {
        val codePoint = line.codePointAt(index)
        val byteCount = utf8Length(codePoint)
        val limit = if (continuation) MAX_LINE_OCTETS - 1 else MAX_LINE_OCTETS
        if (bytesUsed + byteCount > limit) {
            writer.write(buffer.toString())
            writer.write("\r\n ")
            buffer.setLength(0)
            bytesUsed = 0
            continuation = true
        }
        buffer.appendCodePoint(codePoint)
        bytesUsed += byteCount
        index += Character.charCount(codePoint)
    }
    writer.write(buffer.toString())
    writer.write("\r\n")
}

private fun utf8Length(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

/**
 * Parses the VEVENTs of one iCalendar document, in file order. Unsupported rules (for example
 * MONTHLY) and malformed VEVENTs are skipped rather than rejecting the whole file; a document
 * with no VEVENT, or with more than [MAX_ICS_EVENTS], throws.
 */
internal fun parseIcs(text: String, zone: ZoneId = ZoneId.systemDefault()): List<IcsEventDraft> {
    val lines = unfold(text)
    val drafts = mutableListOf<IcsEventDraft>()
    var index = 0
    var eventCount = 0
    while (index < lines.size) {
        if (!lines[index].trim().equals("BEGIN:VEVENT", ignoreCase = true)) {
            index++
            continue
        }
        val block = mutableListOf<String>()
        index++
        while (index < lines.size && !lines[index].trim().equals("END:VEVENT", ignoreCase = true)) {
            block += lines[index]
            index++
        }
        index++
        eventCount++
        if (eventCount > MAX_ICS_EVENTS) {
            throw IcsException("The calendar contains more than $MAX_ICS_EVENTS events")
        }
        parseVEvent(block, zone)?.let { drafts += it }
    }
    if (eventCount == 0) throw IcsException("No events were found in the calendar file")
    return drafts
}

private fun unfold(text: String): List<String> {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = mutableListOf<String>()
    for (line in normalized.split('\n')) {
        if (line.startsWith(' ') || line.startsWith('\t')) {
            if (lines.isNotEmpty()) {
                lines[lines.lastIndex] += line.substring(1)
            }
        } else {
            lines += line
        }
    }
    return lines
}

private data class Property(
    val name: String,
    val params: Map<String, String>,
    val value: String,
)

private fun parseProperty(line: String): Property? {
    if (line.isBlank()) return null
    var inQuotes = false
    var colon = -1
    for (index in line.indices) {
        when (line[index]) {
            '"' -> inQuotes = !inQuotes
            ':' -> if (!inQuotes) {
                colon = index
                break
            }
        }
    }
    if (colon <= 0) return null
    val head = line.substring(0, colon)
    val value = line.substring(colon + 1)
    val parts = head.split(';')
    var name = parts.first().uppercase(Locale.ROOT)
    val groupSeparator = name.lastIndexOf('.')
    if (groupSeparator >= 0) name = name.substring(groupSeparator + 1)
    val params = mutableMapOf<String, String>()
    for (part in parts.drop(1)) {
        val equals = part.indexOf('=')
        if (equals <= 0) continue
        val key = part.substring(0, equals).trim().uppercase(Locale.ROOT)
        val rawValue = part.substring(equals + 1).trim()
        params[key] = rawValue.removeSurrounding("\"")
    }
    return Property(name, params, value)
}

private fun parseVEvent(block: List<String>, zone: ZoneId): IcsEventDraft? {
    val properties = mutableListOf<Property>()
    val alarms = mutableListOf<MutableList<Property>>()
    var currentAlarm: MutableList<Property>? = null
    for (line in block) {
        val property = parseProperty(line) ?: continue
        when {
            property.name == "BEGIN" && property.value.equals("VALARM", ignoreCase = true) -> {
                currentAlarm = mutableListOf<Property>().also { alarms += it }
            }
            property.name == "END" && property.value.equals("VALARM", ignoreCase = true) -> {
                currentAlarm = null
            }
            currentAlarm != null -> currentAlarm.add(property)
            else -> properties += property
        }
    }

    val start = properties.firstOrNull { it.name == "DTSTART" } ?: return null
    val startValue = parseDateTime(start, zone) ?: return null
    val endValue = properties.firstOrNull { it.name == "DTEND" }?.let { parseDateTime(it, zone) }
    val startDateTime = startValue.dateTime
    val startDate = startDateTime.toLocalDate()

    val title = unescapeText(properties.firstOrNull { it.name == "SUMMARY" }?.value.orEmpty())
    val note = unescapeText(properties.firstOrNull { it.name == "DESCRIPTION" }?.value.orEmpty())
    val repeat = parseRecurrence(
        properties.firstOrNull { it.name == "RRULE" }?.value,
        startDate,
        zone,
    ) ?: RecurrenceRule(Event.REPEAT_NONE)
    val reminderMinutes = parseReminder(alarms, startDateTime, zone)

    if (startValue.isDateOnly) {
        val endDate = endValue?.dateTime?.toLocalDate()
        val days = if (endDate == null) {
            1L
        } else {
            ChronoUnit.DAYS.between(startDate, endDate)
        }
        val span = days.coerceIn(1L, Event.MAX_EVENT_DAY_SPAN.toLong()).toInt()
        return if (span == 1) {
            IcsEventDraft(
                title = title,
                note = note,
                date = startDate,
                start = LocalTime.MIDNIGHT,
                end = LocalTime.MIDNIGHT,
                endDayOffset = 0,
                allDay = true,
                repeat = repeat,
                reminderMinutes = Event.REMINDER_OFF,
            )
        } else {
            // Clockwise has no multi-day all-day event, so keep the span as a midnight-to-midnight
            // timed event instead of dropping the extra days.
            IcsEventDraft(
                title = title,
                note = note,
                date = startDate,
                start = LocalTime.MIDNIGHT,
                end = LocalTime.MIDNIGHT,
                endDayOffset = span,
                allDay = false,
                repeat = repeat,
                reminderMinutes = Event.REMINDER_OFF,
            )
        }
    }

    val startOfDay = startDate.atStartOfDay()
    val rawEnd = endValue?.dateTime ?: startDateTime
    val endDateTime = if (rawEnd.isAfter(startDateTime)) rawEnd else startDateTime
    var endSeconds = Duration.between(startOfDay, endDateTime).seconds
    val maxEndSeconds = (Event.MAX_EVENT_DAY_SPAN * Event.SECONDS_PER_DAY).toLong()
    if (endSeconds > maxEndSeconds) endSeconds = maxEndSeconds
    if (endSeconds < 0) endSeconds = startDateTime.toLocalTime().toSecondOfDay().toLong()
    val clampedEnd = endSeconds.toInt()
    return IcsEventDraft(
        title = title,
        note = note,
        date = startDate,
        start = startDateTime.toLocalTime(),
        end = LocalTime.ofSecondOfDay((clampedEnd % Event.SECONDS_PER_DAY).toLong()),
        endDayOffset = clampedEnd / Event.SECONDS_PER_DAY,
        allDay = false,
        repeat = repeat,
        reminderMinutes = reminderMinutes,
    )
}

private data class IcsDateTime(val dateTime: LocalDateTime, val isDateOnly: Boolean)

private fun parseDateTime(property: Property, zone: ZoneId): IcsDateTime? {
    val raw = property.value.trim()
    if (raw.isEmpty()) return null
    val declaredDate = property.params["VALUE"]?.equals("DATE", ignoreCase = true) == true
    if (declaredDate || !raw.contains('T')) {
        val date = parseDate(raw) ?: return null
        return IcsDateTime(date.atStartOfDay(), true)
    }
    val isUtc = raw.endsWith("Z", ignoreCase = true)
    val core = if (isUtc) raw.dropLast(1) else raw
    val date = parseDate(core.substringBefore('T')) ?: return null
    val time = parseTime(core.substringAfter('T', "")) ?: return null
    val local = LocalDateTime.of(date, time)
    val converted = when {
        isUtc -> local.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime()
        property.params["TZID"] != null -> {
            val sourceZone = runCatching { ZoneId.of(property.params.getValue("TZID")) }.getOrNull()
            if (sourceZone == null) local
            else local.atZone(sourceZone).withZoneSameInstant(zone).toLocalDateTime()
        }
        else -> local
    }
    return IcsDateTime(converted, false)
}

private fun parseDate(raw: String): LocalDate? {
    if (raw.length != 8 || !raw.all { it.isDigit() }) return null
    val year = raw.substring(0, 4).toIntOrNull() ?: return null
    val month = raw.substring(4, 6).toIntOrNull() ?: return null
    val day = raw.substring(6, 8).toIntOrNull() ?: return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

private fun parseTime(raw: String): LocalTime? {
    if (raw.length != 4 && raw.length != 6) return null
    if (!raw.all { it.isDigit() }) return null
    val hour = raw.substring(0, 2).toIntOrNull() ?: return null
    val minute = raw.substring(2, 4).toIntOrNull() ?: return null
    val second = if (raw.length == 6) raw.substring(4, 6).toIntOrNull() ?: return null else 0
    return runCatching { LocalTime.of(hour, minute, second) }.getOrNull()
}

private fun parseRecurrence(raw: String?, base: LocalDate, zone: ZoneId): RecurrenceRule? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(';').mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals <= 0) return@mapNotNull null
        part.substring(0, equals).trim().uppercase(Locale.ROOT) to
            part.substring(equals + 1).trim()
    }.toMap()
    val type = when (parts["FREQ"]?.uppercase(Locale.ROOT)) {
        "DAILY" -> Event.REPEAT_DAILY
        "WEEKLY" -> Event.REPEAT_WEEKLY
        else -> return null
    }
    var days = parseByDay(parts["BYDAY"])
    if (type == Event.REPEAT_WEEKLY && days.isEmpty()) {
        days = setOf(base.dayOfWeek.value)
    }
    val requestedUntil = parts["UNTIL"]?.let { parseUntil(it, zone) }
    val horizon = base.plusDays(7L * MAX_OCCURRENCES_PER_SERIES)
    val limit = if (requestedUntil != null && requestedUntil.isBefore(horizon)) {
        requestedUntil
    } else {
        horizon
    }
    val until = lastRepresentableDate(base, type, days, limit) ?: return null
    return RecurrenceRule(type, days, until.toEpochDay())
}

private fun parseByDay(raw: String?): Set<Int> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(',')
        .mapNotNull { token ->
            when (token.trim().takeLast(2).uppercase(Locale.ROOT)) {
                "MO" -> 1
                "TU" -> 2
                "WE" -> 3
                "TH" -> 4
                "FR" -> 5
                "SA" -> 6
                "SU" -> 7
                else -> null
            }
        }
        .toSet()
}

private fun parseUntil(raw: String, zone: ZoneId): LocalDate? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (!value.contains('T')) return parseDate(value)
    val isUtc = value.endsWith("Z", ignoreCase = true)
    val core = if (isUtc) value.dropLast(1) else value
    val date = parseDate(core.substringBefore('T')) ?: return null
    val time = parseTime(core.substringAfter('T', "")) ?: LocalTime.MIDNIGHT
    val local = LocalDateTime.of(date, time)
    return if (isUtc) {
        local.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate()
    } else {
        local.toLocalDate()
    }
}

/**
 * Last following occurrence that fits the series cap, so `planRecurrence` never reports a
 * truncated series. An unbounded RRULE is capped the same way instead of importing forever.
 */
private fun lastRepresentableDate(
    base: LocalDate,
    type: Int,
    days: Set<Int>,
    until: LocalDate,
): LocalDate? {
    if (!until.isAfter(base)) return null
    val maxFollowing = MAX_OCCURRENCES_PER_SERIES - 1
    var date = base.plusDays(1)
    var found = 0
    var last: LocalDate? = null
    while (!date.isAfter(until)) {
        val matches = when (type) {
            Event.REPEAT_DAILY -> true
            Event.REPEAT_WEEKLY -> date.dayOfWeek.value in days
            else -> false
        }
        if (matches) {
            if (found >= maxFollowing) break
            found++
            last = date
        }
        date = date.plusDays(1)
    }
    return last
}

private fun parseReminder(
    alarms: List<List<Property>>,
    start: LocalDateTime,
    zone: ZoneId,
): Int {
    for (alarm in alarms) {
        val action = alarm.firstOrNull { it.name == "ACTION" }?.value?.trim()?.uppercase(Locale.ROOT)
        if (action != null && action != "DISPLAY") continue
        val trigger = alarm.firstOrNull { it.name == "TRIGGER" } ?: continue
        val minutes = parseTrigger(trigger, start, zone) ?: continue
        if (minutes in Event.MIN_REMINDER_MINUTES..Event.MAX_REMINDER_MINUTES) return minutes
    }
    return Event.REMINDER_OFF
}

private val DURATION_PATTERN = Regex(
    "^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$",
)

private fun parseTrigger(trigger: Property, start: LocalDateTime, zone: ZoneId): Int? {
    val value = trigger.value.trim()
    if (value.isEmpty()) return null
    if (value.first().isDigit()) {
        val absolute = parseDateTime(trigger, zone)?.dateTime ?: return null
        val minutes = Duration.between(absolute, start).toMinutes()
        return if (minutes > 0) minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
    }
    val match = DURATION_PATTERN.matchEntire(value) ?: return null
    if (match.groupValues[1] == "+") return null
    val weeks = match.groupValues[2].toLongOrNull() ?: 0L
    val days = match.groupValues[3].toLongOrNull() ?: 0L
    val hours = match.groupValues[4].toLongOrNull() ?: 0L
    val minutes = match.groupValues[5].toLongOrNull() ?: 0L
    val seconds = match.groupValues[6].toLongOrNull() ?: 0L
    val total = weeks * 10_080 + days * 1_440 + hours * 60 + minutes + seconds / 60
    if (total <= 0) return null
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun unescapeText(value: String): String {
    if (!value.contains('\\')) return value
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '\\' && index + 1 < value.length) {
            when (val next = value[index + 1]) {
                'n', 'N' -> result.append('\n')
                '\\' -> result.append('\\')
                ',', ';' -> result.append(next)
                else -> {
                    result.append(character)
                    result.append(next)
                }
            }
            index += 2
        } else {
            result.append(character)
            index++
        }
    }
    return result.toString()
}
