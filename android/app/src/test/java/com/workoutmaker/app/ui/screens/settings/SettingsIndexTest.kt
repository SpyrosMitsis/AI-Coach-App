package com.workoutmaker.app.ui.screens.settings

import com.workoutmaker.app.data.AppSettings
import com.workoutmaker.app.data.DayAvailability
import com.workoutmaker.app.data.InjuryEntry
import com.workoutmaker.app.data.LlmProvider
import com.workoutmaker.app.data.Race
import com.workoutmaker.app.data.StartingLift
import com.workoutmaker.app.data.TrainingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = LocalDate.of(2026, 7, 30)

private fun snap(
    profile: TrainingProfile = TrainingProfile(),
    races: List<Race> = emptyList(),
    hasKey: Boolean = true,
    isPro: Boolean = false,
    intervals: Boolean = false,
    health: Boolean = false,
    autoPlan: Boolean = false,
    knowledgeLines: Int = 0,
    settings: AppSettings = AppSettings(),
    hushedNumbers: Set<String> = emptySet(),
) = SettingsSnapshot(
    profile = profile,
    races = races,
    provider = LlmProvider.GROQ,
    hasProviderKey = hasKey,
    isPro = isPro,
    intervalsConnected = intervals,
    healthConnected = health,
    autoPlan = autoPlan,
    knowledgeLines = knowledgeLines,
    settings = settings,
    today = TODAY,
    hushedNumbers = hushedNumbers,
)

class SettingsRowValueTest {

    // The point of the redesign: a row answers "what is this set to" without
    // being opened. An empty profile must still say something true.
    @Test
    fun `an untouched profile reports every unset row as unfinished, not as blank`() {
        val s = snap(hasKey = false)
        listOf("profile", "sports", "week", "ai", "connections").forEach { id ->
            val v = settingsRowValue(id, s)
            assertTrue("$id must say something", v.text.isNotBlank())
            assertTrue("$id must read as unfinished", v.unfinished)
        }
    }

    @Test
    fun `a filled profile reports its own numbers`() {
        val s = snap(
            profile = TrainingProfile(
                birth_year = 1992,
                weight_kg = 74,
                sports = listOf("run", "strength"),
                experience_by_sport = mapOf("run" to "Intermediate"),
                day_availability = listOf(
                    DayAvailability("Mon", 60), DayAvailability("Wed", 60),
                    DayAvailability("Fri", 60), DayAvailability("Sat", 120),
                ),
                threshold_pace_per_km = "5:22",
                starting_lifts = listOf(StartingLift("Back Squat", 100.0, 5)),
            ),
        )
        assertEquals("34 y · 74 kg", settingsRowValue("profile", s).text)
        assertEquals("2 sports · Intermediate", settingsRowValue("sports", s).text)
        assertEquals("4 days · 1h", settingsRowValue("week", s).text)
        assertTrue(!settingsRowValue("zones", s).unfinished)
    }

    @Test
    fun `zones names the first anchor this athlete's own sports are missing`() {
        val runner = snap(profile = TrainingProfile(sports = listOf("run")))
        assertEquals("Threshold pace missing", settingsRowValue("zones", runner).text)
        assertTrue(settingsRowValue("zones", runner).unfinished)

        val cyclist = snap(profile = TrainingProfile(sports = listOf("ride")))
        assertEquals("FTP missing", settingsRowValue("zones", cyclist).text)

        // A number a sport does not use is never asked for.
        assertEquals(emptyList<String>(), missingNumbers(TrainingProfile(sports = emptyList())))
    }

    // Bug #88: an athlete who rides but has never tested cannot make "FTP
    // missing" go away by doing anything, so the amber is a permanent reproach.
    @Test
    fun `a hushed number stops being flagged as missing`() {
        val profile = TrainingProfile(sports = listOf("ride"))
        assertTrue(settingsRowValue("zones", snap(profile = profile)).unfinished)

        val quiet = snap(profile = profile, hushedNumbers = setOf("FTP"))
        assertTrue(!settingsRowValue("zones", quiet).unfinished)
        // And it drops out of the "finish setting up" list with it.
        assertTrue(unfinishedSetup(quiet).none { it.first == "zones" })
    }

    @Test
    fun `hushing one number still leaves the others asked for`() {
        val both = TrainingProfile(sports = listOf("run", "ride"))
        val quiet = snap(profile = both, hushedNumbers = setOf("Threshold pace"))
        assertEquals("FTP missing", settingsRowValue("zones", quiet).text)
        // Hushing hides the prompt, never the question: the row is still offered
        // in the editor so the athlete can turn it back on.
        assertTrue("Threshold pace" in applicableNumbers(both))
    }

    @Test
    fun `the next race is the soonest one still ahead, with its countdown`() {
        val s = snap(
            races = listOf(
                Race(name = "Past one", date = "2026-01-01"),
                Race(name = "Rotterdam", date = "2026-10-06"),
                Race(name = "Later still", date = "2027-04-01"),
            ),
        )
        assertEquals("Rotterdam · 68d", settingsRowValue("races", s).text)
        // A race that has been and gone is not the current goal.
        assertEquals("None set", settingsRowValue("races", snap(races = listOf(Race(name = "Past", date = "2020-01-01")))).text)
    }

    @Test
    fun `hard rules are counted from both the structured injuries and the free text`() {
        val s = snap(
            profile = TrainingProfile(
                injuries = listOf(
                    InjuryEntry("Knee", "moderate"),
                    InjuryEntry(area = "", note = "free text does not count as a rule"),
                ),
            ),
            knowledgeLines = 2,
        )
        assertEquals("3 rules", settingsRowValue("knowledge", s).text)
        assertEquals("None", settingsRowValue("knowledge", snap()).text)
    }

    @Test
    fun `a Pro subscriber is never told to add an API key`() {
        val pro = snap(hasKey = false, isPro = true)
        assertEquals("Pro · hosted", settingsRowValue("ai", pro).text)
        assertTrue(!settingsRowValue("ai", pro).unfinished)
        assertTrue(unfinishedSetup(pro).none { it.first == "ai" })
    }
}

class FinishSetupTest {

    @Test
    fun `a brand new account is nudged toward the model first`() {
        val pending = unfinishedSetup(snap(hasKey = false))
        assertEquals("ai", pending.first().first)
        // Every entry explains what it costs the athlete, not just what is empty.
        assertTrue(pending.all { it.second.isNotBlank() })
    }

    @Test
    fun `a fully set up account gets no nudge card at all`() {
        val s = snap(
            profile = TrainingProfile(
                birth_year = 1992,
                weight_kg = 74,
                sports = listOf("run"),
                day_availability = listOf(DayAvailability("Mon", 60)),
                threshold_pace_per_km = "5:22",
            ),
            intervals = true,
        )
        assertEquals(emptyList<Pair<String, String>>(), unfinishedSetup(s))
    }

    @Test
    fun `every nudge points at a row that actually exists`() {
        val ids = SETTINGS_GROUPS.flatMap { it.items }.map { it.id }.toSet()
        unfinishedSetup(snap(hasKey = false)).forEach {
            assertTrue("${it.first} is not a settings row", it.first in ids)
        }
    }
}

// Every detail screen opens by stating where you stand, not by repeating its own
// title. These pin that every screen has such a sentence and that it is true.
class SettingsDetailHeaderTest {

    @Test
    fun `every settings row has a header, and none of them just repeats the title`() {
        val s = snap()
        SETTINGS_GROUPS.flatMap { it.items }.forEach { item ->
            val h = detailHeader(item.id, s)
            assertTrue("${item.id} needs an eyebrow", h.eyebrow.isNotBlank())
            assertTrue("${item.id} needs a headline", h.headline.isNotBlank())
            assertTrue(
                "${item.id} headline just echoes the row title",
                !h.headline.equals(item.title, ignoreCase = true),
            )
        }
    }

    @Test
    fun `the headline is the current state, and it changes when the state does`() {
        val manual = snap(autoPlan = false)
        val auto = snap(autoPlan = true, profile = TrainingProfile(weekly_tss_target = 360))
        assertEquals("You plan by hand", detailHeader("planning", manual).headline)
        assertEquals("I plan Sundays", detailHeader("planning", auto).headline)
        assertEquals("Aiming for ~360 TSS a week.", detailHeader("planning", auto).subtitle)
    }

    @Test
    fun `small counts read as words`() {
        assertEquals("Three sports", detailHeader("sports", snap(profile = TrainingProfile(sports = listOf("run", "ride", "swim")))).headline)
        assertEquals("One sport", detailHeader("sports", snap(profile = TrainingProfile(sports = listOf("run")))).headline)
        assertEquals("Nothing picked yet", detailHeader("sports", snap()).headline)
        assertEquals("Two of three linked", detailHeader("connections", snap(intervals = true, health = true)).headline)
        assertEquals("Nothing linked yet", detailHeader("connections", snap()).headline)
    }

    @Test
    fun `the AI headline names whoever is actually doing the writing`() {
        assertEquals("Groq is writing your plans", detailHeader("ai", snap()).headline)
        assertEquals("Pro is writing your plans", detailHeader("ai", snap(isPro = true)).headline)
        assertEquals("No model yet", detailHeader("ai", snap(hasKey = false)).headline)
    }

    @Test
    fun `the hard-rules headline matches the one the editor itself prints`() {
        // Two places say this sentence; they must not drift.
        val two = listOf(InjuryEntry("Knee", "mild"), InjuryEntry("Shoulder", ""))
        assertEquals(
            injuryCountHeadline(2),
            detailHeader("knowledge", snap(profile = TrainingProfile(injuries = two))).headline,
        )
        // Free text is not a body-area rule and must not be counted as one.
        val textOnly = listOf(InjuryEntry(area = "", note = "mornings only"))
        assertEquals("Nothing to work around", detailHeader("knowledge", snap(profile = TrainingProfile(injuries = textOnly))).headline)
    }

    @Test
    fun `zones says which anchor is missing rather than claiming it is fine`() {
        val runner = snap(profile = TrainingProfile(sports = listOf("run")))
        assertTrue(detailHeader("zones", runner).subtitle!!.startsWith("Threshold pace is still missing"))
        val done = snap(profile = TrainingProfile(sports = listOf("run"), threshold_pace_per_km = "5:22"))
        assertEquals("Your training zones are derived from these.", detailHeader("zones", done).subtitle)
    }
}

class SettingsSearchTest {

    @Test
    fun `an empty query shows everything`() {
        assertEquals(SETTINGS_GROUPS, filterSettings("   "))
    }

    @Test
    fun `search matches the description too, not just the title`() {
        // "keys" appears only in the AI row's description.
        val hits = filterSettings("keys").flatMap { it.items }
        assertEquals(listOf("ai"), hits.map { it.id })
    }

    @Test
    fun `search drops groups with no hits instead of leaving empty headers`() {
        val groups = filterSettings("palette")
        assertEquals(1, groups.size)
        assertEquals(listOf("appearance"), groups.single().items.map { it.id })
    }

    @Test
    fun `a query nothing matches returns nothing rather than everything`() {
        assertEquals(emptyList<SettingsGroup>(), filterSettings("zzzz"))
    }
}
