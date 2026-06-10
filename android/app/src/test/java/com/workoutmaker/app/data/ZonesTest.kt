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
}
