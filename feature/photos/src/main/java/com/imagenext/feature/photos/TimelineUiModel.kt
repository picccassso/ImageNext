package com.imagenext.feature.photos

import com.imagenext.core.model.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Timeline item for display in the Photos grid.
 *
 * The timeline alternates between date headers and media items.
 * Items with incomplete metadata (null lastModified = 0) are grouped
 * under an "Other" bucket so partial sync data does not break grouping.
 */
sealed interface TimelineItem {
    /** A date section header. */
    data class Header(val label: String, val date: LocalDate?) : TimelineItem

    /** A media item within a date section. */
    data class Media(val mediaItem: MediaItem) : TimelineItem
}

data class TimelineDateContext(
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(zone),
    val yesterday: LocalDate = today.minusDays(1),
    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()),
)

fun MediaItem.resolveTimelineDate(context: TimelineDateContext): LocalDate? {
    val timelineTimestamp = captureTimestamp ?: lastModified
    if (timelineTimestamp <= 0) return null
    return Instant.ofEpochMilli(timelineTimestamp)
        .atZone(context.zone)
        .toLocalDate()
}

fun timelineGroupKey(date: LocalDate?): LocalDate = date ?: LocalDate.MIN

fun formatTimelineHeaderLabel(date: LocalDate?, context: TimelineDateContext): String {
    return when {
        date == null -> "Other"
        date == context.today -> "Today"
        date == context.yesterday -> "Yesterday"
        else -> date.format(context.dateFormatter)
    }
}

/**
 * Groups a flat list of [MediaItem]s into date-bucketed [TimelineItem]s.
 *
 * Items are expected to arrive pre-sorted by timeline timestamp DESC
 * (`captureTimestamp ?: lastModified`).
 * Grouping uses the device's default timezone.
 */
fun List<MediaItem>.toTimelineItems(): List<TimelineItem> {
    if (isEmpty()) return emptyList()

    val dateContext = TimelineDateContext()

    val result = mutableListOf<TimelineItem>()
    var currentDateKey: LocalDate? = null // null means no header emitted yet

    for (item in this) {
        val itemDate = item.resolveTimelineDate(dateContext)
        val groupKey = timelineGroupKey(itemDate)

        if (groupKey != currentDateKey) {
            currentDateKey = groupKey
            val label = formatTimelineHeaderLabel(itemDate, dateContext)
            result.add(TimelineItem.Header(label = label, date = itemDate))
        }

        result.add(TimelineItem.Media(mediaItem = item))
    }

    return result
}
