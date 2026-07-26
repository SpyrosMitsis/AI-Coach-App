package com.workoutmaker.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Room caches DailySummary as raw JSON: old cached rows (and an undeployed
// server) have no `debrief` key, new ones do — both must decode.
class DailySummaryDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val base = """
        "date": "2026-07-18",
        "readiness": {"score": 70, "band": "green", "components": {"wellness": 3.5, "hrvDelta": 0.0, "rhrDelta": 0.0}},
        "weekly_load": {"tss": 120, "target": 350},
        "active_llm_provider": "groq"
    """.trimIndent()

    @Test fun decodesWithoutDebrief() {
        val s = json.decodeFromString<DailySummary>("{$base}")
        assertNull(s.debrief)
    }

    @Test fun decodesWithDebrief() {
        val s = json.decodeFromString<DailySummary>(
            """{$base, "debrief": {"kind": "activity", "activity_id": "abc", "date": "2026-07-18",
                "type": "Run", "score": 82, "label": "solid", "feedback": "Nice pacing."}}""",
        )
        assertEquals("activity", s.debrief?.kind)
        assertEquals("abc", s.debrief?.activity_id)
        assertEquals("solid", s.debrief?.label)
    }

    @Test fun decodesStrengthDebriefWithNullActivityId() {
        val s = json.decodeFromString<DailySummary>(
            """{$base, "debrief": {"kind": "strength", "activity_id": null, "date": "2026-07-18",
                "type": "strength", "score": null, "label": "grinder", "feedback": null}}""",
        )
        assertEquals("strength", s.debrief?.kind)
        assertNull(s.debrief?.activity_id)
    }
}
