package com.imagenext.feature.photos

import com.imagenext.core.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimelineUiModelTest {

    private val zone = ZoneId.systemDefault()

    private fun mediaItem(
        remotePath: String,
        lastModified: Long,
        captureTimestamp: Long? = null,
        fileName: String = "photo.jpg",
    ) = MediaItem(
        remotePath = remotePath,
        fileName = fileName,
        mimeType = "image/jpeg",
        size = 1024,
        lastModified = lastModified,
        captureTimestamp = captureTimestamp,
        etag = "etag",
        folderPath = "/photos",
    )

    private fun epochMillisForDate(date: LocalDate): Long {
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `empty list returns empty timeline`() {
        val result = emptyList<MediaItem>().toTimelineItems()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single item produces one header and one photo`() {
        val today = LocalDate.now(zone)
        val items = listOf(mediaItem("/a.jpg", epochMillisForDate(today)))

        val timeline = items.toTimelineItems()

        assertEquals(2, timeline.size)
        assertTrue(timeline[0] is TimelineItem.Header)
        assertEquals("Today", (timeline[0] as TimelineItem.Header).label)
        assertTrue(timeline[1] is TimelineItem.Photo)
    }

    @Test
    fun `items on same date share one header`() {
        val today = LocalDate.now(zone)
        val millis = epochMillisForDate(today)
        val items = listOf(
            mediaItem("/a.jpg", millis + 1000),
            mediaItem("/b.jpg", millis + 2000),
        )

        val timeline = items.toTimelineItems()

        assertEquals(3, timeline.size) // 1 header + 2 photos
        assertTrue(timeline[0] is TimelineItem.Header)
        assertTrue(timeline[1] is TimelineItem.Photo)
        assertTrue(timeline[2] is TimelineItem.Photo)
    }

    @Test
    fun `items on different dates get separate headers`() {
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val items = listOf(
            mediaItem("/a.jpg", epochMillisForDate(today)),
            mediaItem("/b.jpg", epochMillisForDate(yesterday)),
        )

        val timeline = items.toTimelineItems()

        assertEquals(4, timeline.size) // 2 headers + 2 photos
        assertEquals("Today", (timeline[0] as TimelineItem.Header).label)
        assertEquals("Yesterday", (timeline[2] as TimelineItem.Header).label)
    }

    @Test
    fun `yesterday label is applied correctly`() {
        val yesterday = LocalDate.now(zone).minusDays(1)
        val items = listOf(mediaItem("/a.jpg", epochMillisForDate(yesterday)))

        val timeline = items.toTimelineItems()

        assertEquals("Yesterday", (timeline[0] as TimelineItem.Header).label)
    }

    @Test
    fun `older dates use formatted label`() {
        val oldDate = LocalDate.of(2024, 6, 15)
        val items = listOf(mediaItem("/a.jpg", epochMillisForDate(oldDate)))

        val timeline = items.toTimelineItems()

        val header = timeline[0] as TimelineItem.Header
        // Should contain "June" and "2024"
        assertTrue(header.label.contains("2024"))
        assertTrue(header.label.contains("15"))
    }

    @Test
    fun `zero lastModified groups under Other`() {
        val items = listOf(mediaItem("/a.jpg", 0))

        val timeline = items.toTimelineItems()

        assertEquals("Other", (timeline[0] as TimelineItem.Header).label)
    }

    @Test
    fun `mixed valid and zero timestamps produce correct grouping`() {
        val today = LocalDate.now(zone)
        val items = listOf(
            mediaItem("/a.jpg", epochMillisForDate(today)),
            mediaItem("/b.jpg", 0),
        )

        val timeline = items.toTimelineItems()

        assertEquals(4, timeline.size) // "Today" header + photo + "Other" header + photo
        assertEquals("Today", (timeline[0] as TimelineItem.Header).label)
        assertEquals("Other", (timeline[2] as TimelineItem.Header).label)
    }

    @Test
    fun `captureTimestamp takes precedence over lastModified for grouping`() {
        val oldDate = LocalDate.of(2023, 5, 1)
        val today = LocalDate.now(zone)
        val items = listOf(
            mediaItem(
                remotePath = "/a.jpg",
                lastModified = epochMillisForDate(today),
                captureTimestamp = epochMillisForDate(oldDate),
            ),
        )

        val timeline = items.toTimelineItems()
        val header = timeline[0] as TimelineItem.Header
        assertTrue(header.label.contains("2023"))
    }
}
