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
    data class Photo(val mediaItem: MediaItem) : TimelineItem
}

/**
 * Groups a flat list of [MediaItem]s into date-bucketed [TimelineItem]s.
 *
 * Items are expected to arrive pre-sorted by lastModified DESC.
 * Grouping uses the device's default timezone.
 */
fun List<MediaItem>.toTimelineItems(): List<TimelineItem> {
    if (isEmpty()) return emptyList()

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val yesterday = today.minusDays(1)
    val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())

    val result = mutableListOf<TimelineItem>()
    var currentDateKey: LocalDate? = null // null means no header emitted yet

    for (item in this) {
        val itemDate = if (item.lastModified > 0) {
            Instant.ofEpochMilli(item.lastModified)
                .atZone(zone)
                .toLocalDate()
        } else {
            null
        }

        // Determine the grouping key — null lastModified groups under a special bucket
        val groupKey = itemDate ?: LocalDate.MIN

        if (groupKey != currentDateKey) {
            currentDateKey = groupKey
            val label = when {
                itemDate == null -> "Other"
                itemDate == today -> "Today"
                itemDate == yesterday -> "Yesterday"
                else -> itemDate.format(dateFormatter)
            }
            result.add(TimelineItem.Header(label = label, date = itemDate))
        }

        result.add(TimelineItem.Photo(mediaItem = item))
    }

    return result
}
