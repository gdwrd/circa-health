package com.nsheremet.circa

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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

class ReportDaysTest {
    @Test fun keepsFourteenDays() {
        val x = JsonPrimitive(1)
        val kept = keepDays(mapOf("2026-11-15/today" to x, "2026-11-02/week" to x, "2026-11-01/today" to x), LocalDate.parse("2026-11-15"))
        assertEquals(setOf("2026-11-15/today", "2026-11-02/week"), kept.keys)
    }
}
