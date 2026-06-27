package com.workoutmaker.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZonesTest {
    @Test fun parseAndFormatPace() {
        assertEquals(270, Zones.parsePace("4:30"))
        assertEquals("4:30", Zones.formatPace(270))
        assertNull(Zones.parsePace("4:60"))
        assertNull(Zones.parsePace("abc"))
    }

    @Test fun hrZonesCoverFromLowToAboveThreshold() {
        val z = Zones.hrZonesFromLthr(170)
        assertEquals(5, z.size)
        assertEquals("Z4 Threshold", z[3].name)
        // Z4 starts at 94% of 170 ≈ 160
        assertEquals(160, z[3].min)
        // Z5 tops out above LTHR
        assertTrue(z.last().max > 170)
    }

    @Test fun paceZonesAreSlowerToFaster() {
        val z = Zones.paceZonesFromThreshold(240) // 4:00/km threshold
        assertEquals(5, z.size)
        // Easy zone is slower (bigger seconds) than threshold; interval is faster.
        assertTrue(z.first().slowSec > 240)
        assertTrue(z.last().fastSec < 240)
    }

    @Test fun powerZonesFromFtp() {
        val z = Zones.powerZonesFromFtp(250)
        assertEquals(5, z.size)
        // Z4 threshold band brackets FTP.
        assertTrue(z[3].min <= 250 && z[3].max >= 250)
    }

    @Test fun parseDurationSecHandlesTimeNotDistance() {
        assertEquals(180, Zones.parseDurationSec("3min"))
        assertEquals(600, Zones.parseDurationSec("10 min"))
        assertEquals(600, Zones.parseDurationSec("10m"))
        assertEquals(90, Zones.parseDurationSec("90s"))
        assertEquals(300, Zones.parseDurationSec("5:00"))
        assertEquals(3600, Zones.parseDurationSec("1h"))
        assertNull(Zones.parseDurationSec("1km"))   // distance, not time
        assertNull(Zones.parseDurationSec("8-10"))   // rep count
        assertNull(Zones.parseDurationSec(""))
    }

    @Test fun zoneNumExtractsOneToFive() {
        assertEquals(4, Zones.zoneNum("Z4"))
        assertEquals(2, Zones.zoneNum("Zone 2 - aerobic"))
        assertNull(Zones.zoneNum("warmup"))
    }

    @Test fun targetRangePrefersPaceThenHr() {
        // 4:00/km threshold → Z4 pace band is a real /km range.
        val pace = Zones.targetRange("Z4", "Z4", thresholdSecPerKm = 240, lthr = 170)
        assertTrue(pace!!.contains("/km"))
        // No pace threshold → falls back to HR band.
        val hr = Zones.targetRange("Z4", "Z4", thresholdSecPerKm = null, lthr = 170)
        assertTrue(hr!!.contains("bpm"))
        // Neither threshold → null.
        assertNull(Zones.targetRange("Z4", "Z4", thresholdSecPerKm = null, lthr = null))
    }

    @Test fun fmtDurationShortReadsCleanly() {
        assertEquals("3 min", Zones.fmtDurationShort(180))
        assertEquals("45s", Zones.fmtDurationShort(45))
        assertEquals("1:30", Zones.fmtDurationShort(90))
        assertEquals("", Zones.fmtDurationShort(0))
    }
}
