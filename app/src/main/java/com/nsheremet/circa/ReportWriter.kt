package com.nsheremet.circa

import android.app.DownloadManager
import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.nsheremet.circa.Prefs.reportName
import com.nsheremet.circa.Prefs.reportWriter
import com.nsheremet.circa.ui.Api
import com.nsheremet.circa.ui.checkDownload
import com.nsheremet.circa.ui.reportModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

// AI5: Gemma on the phone turns each /report card into a note; reports.json keeps them for the Reports page.

private val kindFocus = mapOf(
    "today" to "how their body recovered overnight and how today is likely to feel",
    "yesterday" to "how yesterday went for their body",
    "night" to "how the night went and what shaped it",
    "tonight" to "what to do this evening and why",
    "week" to "how the week went for their body and what stands out",
    "next_week" to "the one thing to focus on next week and why",
    "body" to "what their body's signals show, said calmly",
)

/** System instruction: role, length and rules for one card. */
internal fun reportSystem(name: String, kind: String) = buildString {
    val who = name.trim().ifEmpty { "the user" }
    append("You write a short personal note to $who about how their body is doing, based on what their ring measured. ")
    append("Write to them as \"you\"")
    append(if (kind == "today" && name.isNotBlank()) " and open with their name.\n" else ".\n")
    append("Length: ${if (kind == "today") "5 to 7" else "3 to 5"} sentences in one paragraph. No lists, no headings, no markdown.\n")
    append("Do not list the facts one by one. Connect them into an explanation of how the body is doing and why, then what that means, then the advice.\n")
    append("Focus: ${kindFocus[kind] ?: "how their body is doing"}.\n")
    append("Use plain short sentences, no 'indicating' or 'suggesting'. Keep words like 'can', 'may' and 'likely' as they are.\n")
    append("Rules: use only the facts and advice given. When you use a number or a time, copy it exactly as written, in digits. ")
    append("Never mention a score. No medical advice, no illness names. Do not invent advice. Give every advice line. ")
    append("If no advice is given, end with an observation, not a suggestion.")
}

/** User message: the card's facts and advice. */
internal fun reportPrompt(verdict: String?, facts: List<String>, advice: List<String>, fallback: String) = buildString {
    if (verdict != null) append("Main message: ${fallback.split(Regex("(?<=[.!?])\\s")).first()}\n")
    append("Facts:\n")
    facts.forEach { append("- $it\n") }
    if (advice.isNotEmpty()) {
        append("Advice to give, in your own words:\n")
        advice.forEach { append("- $it\n") }
    }
}

/** What a note depends on: a card is rewritten only when this changes. */
internal fun reportHash(name: String, verdict: String?, facts: List<String>, advice: List<String>): String =
    MessageDigest.getInstance("SHA-1").digest(listOf(name.trim(), verdict.orEmpty(), facts.joinToString("\n"), advice.joinToString("\n"))
        .joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

private val digits = Regex("\\d+")
// "one" is left out on purpose: too common as an ordinary word.
private val numberWords = Regex("\\b(two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|" +
    "seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand)\\b", RegexOption.IGNORE_CASE)
private val banned = Regex("score|diagnos|\\b(oura|diseases?|illness|sick|sickness|infection|covid|flu|fever|apnea|apnoea|insomnia|depression|anxiety|" +
    "disorders?|arrhythmia|afib|diabetes|hypertension|cancer)\\b", RegexOption.IGNORE_CASE)
private val sentenceEnd = Regex("[.!?](?=\\s|$)")

/** Every run of digits and every spelled-out number in [text] is also in [facts] or [advice]. */
internal fun numbersOk(text: String, facts: List<String>, advice: List<String>): Boolean {
    val src = (facts + advice).joinToString(" ")
    val nums = digits.findAll(src).map { it.value }.toSet()
    val words = numberWords.findAll(src).map { it.value.lowercase(Locale.ROOT) }.toSet()
    return digits.findAll(text).all { it.value in nums } && numberWords.findAll(text).all { it.value.lowercase(Locale.ROOT) in words }
}

internal fun sentenceCount(text: String): Int = text.trim().split(Regex("(?<=[.!?])\\s+")).count { it.isNotBlank() }

/** [text] at most [max] characters, cut after the last sentence end that fits ("" when none does). */
internal fun cut(text: String, max: Int = 900): String =
    if (text.length <= max) text else sentenceEnd.findAll(text).lastOrNull { it.range.last < max }?.let { text.take(it.range.last + 1) }.orEmpty()

/** The note to keep, or null when a check fails and the card shows its fallback. */
internal fun checkedNote(reply: String, facts: List<String>, advice: List<String>): String? {
    val text = cut(reply.trim())
    return text.takeIf { numbersOk(it, facts, advice) && !banned.containsMatchIn(it) && sentenceCount(it) >= 2 }
}

/** `reports.json` entries kept: the 14 days ending on [date]. Keys are `date/kind`. */
internal fun keepDays(reports: Map<String, JsonElement>, date: LocalDate) =
    reports.filterKeys { it.substringBefore('/') >= date.minusDays(13).toString() }

/**
 * Writes the notes of today's `/report` cards whose hash changed into `filesDir/reports.json`
 * (`{"date/kind": {"hash", "text"}}`; text is the card's fallback when a check failed).
 * Returns the Sync log line, or null when the writer is off, the model is missing or nothing changed. Blocks for seconds a card.
 */
internal suspend fun writeReports(ctx: Context): String? {
    if (!ctx.reportWriter) return null
    runCatching { checkDownload(ctx, ctx.getSystemService(DownloadManager::class.java)) } // a download that ended while Settings was closed
    val model = reportModel(ctx)
    if (!model.exists()) return null
    val reply = Api.report(ctx, LocalDate.now()) as? JsonObject ?: return null
    val date = (reply["date"] as? JsonPrimitive)?.content ?: return null
    val file = File(ctx.filesDir, "reports.json")
    val reports = keepDays(runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap())), LocalDate.parse(date)).toMutableMap()
    val name = ctx.reportName
    val todo = (reply["cards"] as? JsonArray).orEmpty().map { it.jsonObject }.filter { c ->
        val h = reportHash(name, (c["verdict"] as? JsonPrimitive)?.contentOrNull, c.strings("facts"), c.strings("advice"))
        ((reports["$date/${c.str("kind")}"] as? JsonObject)?.get("hash") as? JsonPrimitive)?.content != h
    }
    if (todo.isEmpty()) return null
    val t0 = SystemClock.elapsedRealtime()
    var fallbacks = 0
    Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU())).use { engine ->
        engine.initialize()
        for (c in todo) {
            val kind = c.str("kind")
            val verdict = (c["verdict"] as? JsonPrimitive)?.contentOrNull
            val facts = c.strings("facts")
            val advice = c.strings("advice")
            val fallback = c.str("fallback")
            val cfg = ConversationConfig(systemInstruction = Contents.of(reportSystem(name, kind)),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.4))
            val note = engine.createConversation(cfg).use { checkedNote(it.sendMessage(reportPrompt(verdict, facts, advice, fallback)).toString(), facts, advice) }
            if (note == null) fallbacks++
            reports["$date/$kind"] = JsonObject(mapOf("hash" to JsonPrimitive(reportHash(name, verdict, facts, advice)), "text" to JsonPrimitive(note ?: fallback)))
            // Saved after every card, through a rename: a killed worker keeps what it wrote and the page never reads half a file.
            File(ctx.filesDir, "reports.json.tmp").apply { writeText(JsonObject(reports).toString()) }.renameTo(file)
        }
    }
    return String.format(Locale.ROOT, "Reports: wrote %d cards for %s in %.1f s, %d fallbacks",
        todo.size, date, (SystemClock.elapsedRealtime() - t0) / 1000.0, fallbacks)
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
