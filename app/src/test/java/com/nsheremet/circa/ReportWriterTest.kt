package com.nsheremet.circa

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReportChecksTest {
    private val facts = listOf("HRV 43 ms, 9 % above your usual", "Slept 5 h 40 min, the most in the last two weeks")
    private val advice = listOf("Be in bed by 22:45 to pay back some sleep debt")

    @Test fun numbersFromFacts() = assertTrue(numbersOk("Your HRV of 43 ms is 9 % up; you slept 5 h 40 min.", facts, advice))
    @Test fun numbersFromAdvice() = assertTrue(numbersOk("Aim to be in bed by 22:45 tonight.", facts, advice))
    @Test fun newNumber() = assertFalse(numbersOk("Sleep 8 hours tonight.", facts, advice))
    @Test fun partOfANumber() = assertFalse(numbersOk("HRV 4.", facts, advice))
    @Test fun numberWordInFacts() = assertTrue(numbersOk("That is your longest night in the last Two weeks.", facts, advice))
    @Test fun numberWordNotInFacts() = assertFalse(numbersOk("Your HRV was seventy-three.", listOf("HRV 73 ms"), advice))
    @Test fun oneIsIgnored() = assertTrue(numbersOk("One of your best nights.", facts, advice))
    @Test fun insideWords() = assertTrue(numbersOk("Someone often feels tension.", facts, advice))

    @Test fun firstTwoSentences() {
        assertEquals("You slept 7 h 8 min. HRV was 43.5 ms!", firstSentences(" You slept 7 h 8 min. HRV was 43.5 ms! Why not rest? "))
        assertEquals("One line, no end", firstSentences("One line, no end"))
    }

    @Test fun sentences() {
        assertEquals(3, sentenceCount("You slept 7 h 8 min. HRV was 43.5 ms! Why not rest?"))
        assertEquals(1, sentenceCount("You fell asleep at 01:21, 9.5 min late."))
    }

    @Test fun goodNote() = assertEquals("You slept 5 h 40 min. Your HRV of 43 ms is up.",
        checkedNote("  You slept 5 h 40 min. Your HRV of 43 ms is up.\n", facts, advice))
    @Test fun scoreRejected() = assertNull(checkedNote("Your readiness score is high. Rest well.", facts, advice))
    @Test fun bannedRejected() = assertNull(checkedNote("This is not insomnia. Rest well.", facts, advice))
    @Test fun ouraRejected() = assertNull(checkedNote("Oura would agree. Rest well.", facts, advice))
    @Test fun bannedOnlyAsWord() = assertEquals("Small dips fluctuate. They encourage rest.",
        checkedNote("Small dips fluctuate. They encourage rest.", facts, advice))
    @Test fun oneSentenceRejected() = assertNull(checkedNote("Rest well.", facts, advice))
}

class ReportCutTest {
    @Test fun shortIsKept() = assertEquals("A b. C d.", cut("A b. C d.", 20))
    @Test fun cutAtLastSentenceEnd() = assertEquals("One two. Three four.", cut("One two. Three four. Five six seven.", 25))
    @Test fun decimalIsNoSentenceEnd() = assertEquals("HRV up.", cut("HRV up. HRV was 43.5 ms and more", 20))
    @Test fun noSentenceEndFits() = assertEquals("", cut("Onelongwordwithoutanend and more.", 10))
    @Test fun endExactlyAtMax() = assertEquals("Ab. Cd.", cut("Ab. Cd. Ef.", 7))
}

class ReportHashTest {
    private val h = reportHash("Ann", "rest", listOf("a", "b"), listOf("c"))

    @Test fun stable() = assertEquals(h, reportHash("Ann", "rest", listOf("a", "b"), listOf("c")))
    @Test fun nameTrimmed() = assertEquals(h, reportHash(" Ann ", "rest", listOf("a", "b"), listOf("c")))
    @Test fun name() = assertNotEquals(h, reportHash("Bob", "rest", listOf("a", "b"), listOf("c")))
    @Test fun verdict() = assertNotEquals(h, reportHash("Ann", "easy", listOf("a", "b"), listOf("c")))
    @Test fun noVerdict() = assertNotEquals(h, reportHash("Ann", null, listOf("a", "b"), listOf("c")))
    @Test fun fact() = assertNotEquals(h, reportHash("Ann", "rest", listOf("a", "x"), listOf("c")))
    @Test fun lineMovedToAdvice() = assertNotEquals(h, reportHash("Ann", "rest", listOf("a"), listOf("b", "c")))
}

class ReportUpdateTest {
    private val d = "2026-11-16"
    private val today = JsonObject(mapOf("text" to JsonPrimitive("Morning note."), "at" to JsonPrimitive(1794815000),
        "advice" to JsonArray(listOf(JsonPrimitive("Go easy today")))))
    private val u1 = updateEntry(1794830000, "First update.", listOf("Take a walk"), false)
    private val u2 = updateEntry(1794840000, "Second update.", listOf("No more coffee today", "Take a walk"), false)
    private val reports = mapOf("$d/today" to today, "$d/update-1794840000" to u2, "$d/update-1794830000" to u1,
        "2026-11-15/update-1794700000" to updateEntry(1794700000, "Yesterday.", listOf("Old"), true))

    @Test fun entryShape() = assertEquals("""{"at":1794830000,"text":"First update.","advice":["Take a walk"],"evening":false}""", u1.toString())
    @Test fun updatesOldestFirstOnlyThatDay() = assertEquals(listOf(u1, u2), updates(reports, d))
    @Test fun sinceLatestUpdate() = assertEquals(1794840000L, updateSince(reports, d))
    @Test fun sinceMorningCard() = assertEquals(1794815000L, updateSince(mapOf("$d/today" to today), d))
    @Test fun sinceStartOfDay() = assertEquals(LocalDate.parse(d).atStartOfDay(ZoneId.systemDefault()).toEpochSecond(), updateSince(emptyMap(), d))
    @Test fun givenFromMorningAndUpdates() = assertEquals(listOf("Go easy today", "Take a walk", "No more coffee today"), givenAdvice(reports, d))
    @Test fun givenWithoutAdviceField() = assertEquals(emptyList<String>(), givenAdvice(mapOf("$d/today" to JsonObject(mapOf("text" to JsonPrimitive("x")))), d))
    @Test fun keptWithTheDay() = assertEquals(4, keepDays(reports, LocalDate.parse(d)).size)

    @Test fun promptCarriesEarlierNotes() {
        val p = updatePrompt(listOf("Morning note.", "First update."), listOf("Go easy today"), listOf("A walk at 13:20 for 40 min"), listOf("Keep the rest of the day easy"))
        assertTrue(p, p.contains("Earlier notes today (context, do not repeat):\n- Morning note.\n- First update.\n"))
        assertTrue(p, p.contains("Advice already given today (do not repeat):\n- Go easy today\n"))
        assertTrue(p, p.contains("New since the last note:\n- A walk at 13:20 for 40 min\n"))
        assertTrue(p, p.endsWith("Advice to give now, in your own words:\n- Keep the rest of the day easy\n"))
    }

    @Test fun promptWithoutContext() = assertEquals("New since the last note:\n- Steps 2400\n", updatePrompt(emptyList(), emptyList(), listOf("Steps 2400"), emptyList()))

    @Test fun systemCarriesTimes() {
        val s = updateSystem("Ann", "09:10", "14:30", false)
        assertTrue(s, s.contains("It is 14:30. Say what changed since 09:10 and what to do now.\n"))
        assertTrue(s, s.contains("nothing about medication"))
        assertTrue(updateSystem("", "09:10", "21:30", true).contains("to wind down for the night"))
    }

    @Test fun noteMayUseTheTimes() =
        assertEquals("Since 09:10 you walked. Keep it easy.", checkedNote("Since 09:10 you walked. Keep it easy.", listOf("A walk") + listOf("09:10", "14:30"), emptyList()))
}

class ReportDaysTest {
    @Test fun keepsFourteenDays() {
        val x = JsonPrimitive(1)
        val kept = keepDays(mapOf("2026-11-15/today" to x, "2026-11-02/week" to x, "2026-11-01/today" to x), LocalDate.parse("2026-11-15"))
        assertEquals(setOf("2026-11-15/today", "2026-11-02/week"), kept.keys)
    }

    private fun cards(vararg kinds: String) = kinds.map { JsonObject(mapOf("kind" to JsonPrimitive(it))) }
    private fun kinds(l: List<JsonObject>) = l.map { (it["kind"] as JsonPrimitive).content }
    private val written = mapOf("2026-11-15/night" to JsonPrimitive(1), "2026-11-15/week" to JsonPrimitive(1), "2026-11-14/today" to JsonPrimitive(1))

    @Test fun entryIsNotRewritten() =
        assertEquals(listOf("today"), kinds(toWrite(written, "2026-11-15", cards("night", "today", "week"), false)))

    @Test fun forceRewritesAll() =
        assertEquals(listOf("night", "today", "week"), kinds(toWrite(dropDay(written, "2026-11-15"), "2026-11-15", cards("night", "today", "week"), true)))

    @Test fun waitsForNight() =
        assertEquals(emptyList<String>(), kinds(toWrite(emptyMap(), "2026-11-15", cards("yesterday", "week"), false)))

    @Test fun laterCardWrittenOnce() {
        val first = toWrite(emptyMap(), "2026-11-15", cards("night", "week"), false)
        assertEquals(listOf("night", "week"), kinds(first))
        val after = first.associate { "2026-11-15/${(it["kind"] as JsonPrimitive).content}" to JsonPrimitive(1) }
        assertEquals(listOf("body"), kinds(toWrite(after, "2026-11-15", cards("night", "week", "body"), false)))
        assertEquals(emptyList<String>(), kinds(toWrite(after + ("2026-11-15/body" to JsonPrimitive(1)), "2026-11-15", cards("night", "week", "body"), false)))
    }

    @Test fun dropsOnlyThatDay() {
        val x = JsonPrimitive(1)
        val kept = dropDay(mapOf("2026-11-15/today" to x, "2026-11-15/week" to x, "2026-11-14/today" to x), "2026-11-15")
        assertEquals(setOf("2026-11-14/today"), kept.keys)
    }
}
