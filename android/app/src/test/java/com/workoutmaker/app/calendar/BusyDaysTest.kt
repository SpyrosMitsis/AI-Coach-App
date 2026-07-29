package com.workoutmaker.app.calendar

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyDaysTest {
    // Fixed zone ahead of UTC — the user's real situation, and the case where
    // UTC/local mixups actually bite.
    private val zone: ZoneId = ZoneId.of("Europe/Athens") // UTC+3 in July
    private val from: LocalDate = LocalDate.parse("2026-07-20")

    private fun timed(date: String, fromHm: String, toHm: String): BusySlot {
        val d = LocalDate.parse(date)
        val begin = d.atTime(fromHm.substringBefore(":").toInt(), fromHm.substringAfter(":").toInt())
        val end = d.atTime(toHm.substringBefore(":").toInt(), toHm.substringAfter(":").toInt())
        return BusySlot(
            begin.atZone(zone).toInstant().toEpochMilli(),
            end.atZone(zone).toInstant().toEpochMilli(),
            allDay = false,
        )
    }

    @Test fun `timed event becomes a local-time window on its day`() {
        val out = busyDaysFromSlots(listOf(timed("2026-07-21", "18:00", "20:30")), from, 7, zone)
        assertEquals(1, out.size)
        assertEquals("2026-07-21", out[0].date)
        assertEquals(listOf("18:00-20:30"), out[0].windows)
        assertEquals(false, out[0].all_day)
    }

    @Test fun `all-day event uses UTC anchoring and spans its dates`() {
        // Two-day all-day event 2026-07-25..26: UTC midnights per CalendarContract.
        val begin = LocalDate.parse("2026-07-25").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = LocalDate.parse("2026-07-27").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val out = busyDaysFromSlots(listOf(BusySlot(begin, end, allDay = true)), from, 7, zone)
        assertEquals(listOf("2026-07-25", "2026-07-26"), out.map { it.date })
        assertTrue(out.all { it.all_day })
    }

    @Test fun `event spanning midnight is split and clamped per day`() {
        val begin = LocalDate.parse("2026-07-22").atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDate.parse("2026-07-23").atTime(1, 30).atZone(zone).toInstant().toEpochMilli()
        val out = busyDaysFromSlots(listOf(BusySlot(begin, end, allDay = false)), from, 7, zone)
        assertEquals(listOf("2026-07-22", "2026-07-23"), out.map { it.date })
        assertEquals(listOf("22:00-23:59"), out[0].windows)
        assertEquals(listOf("00:00-01:30"), out[1].windows)
    }

    @Test fun `events outside the requested range are dropped`() {
        val out = busyDaysFromSlots(
            listOf(timed("2026-07-19", "10:00", "11:00"), timed("2026-07-27", "10:00", "11:00")),
            from, 7, zone, // range covers 2026-07-20..26
        )
        assertTrue(out.isEmpty())
    }

    @Test fun `windows on the same day are sorted and days sorted`() {
        val out = busyDaysFromSlots(
            listOf(
                timed("2026-07-24", "14:00", "15:00"),
                timed("2026-07-21", "09:00", "10:00"),
                timed("2026-07-21", "06:30", "07:15"),
            ),
            from, 7, zone,
        )
        assertEquals(listOf("2026-07-21", "2026-07-24"), out.map { it.date })
        assertEquals(listOf("06:30-07:15", "09:00-10:00"), out[0].windows)
    }
}
