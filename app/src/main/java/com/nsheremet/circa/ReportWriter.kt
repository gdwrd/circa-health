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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/** What a note depends on; stored with it, no longer a reason to rewrite (DT1). */
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

/** `reports.json` entries without [date]'s: Regenerate rewrites every card of that day. */
internal fun dropDay(reports: Map<String, JsonElement>, date: String) = reports.filterKeys { !it.startsWith("$date/") }

/**
 * DT1: the [cards] of [date]'s `/report` to write. A card with an entry is never rewritten (Regenerate drops the day first);
 * nothing is written until the `night` card is in, unless [force].
 */
internal fun toWrite(reports: Map<String, JsonElement>, date: String, cards: List<JsonObject>, force: Boolean): List<JsonObject> =
    if (!force && cards.none { it.str("kind") == "night" }) emptyList() else cards.filter { "$date/${it.str("kind")}" !in reports }

/** The first two sentences of [text]: a notification body. */
internal fun firstSentences(text: String): String = text.trim().split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")

// One writer at a time: the Reports page and a sync may both start one; the second waits, then finds nothing to write.
private val writing = Mutex()

/**
 * Writes the notes of today's `/report` cards that have none yet ([toWrite]) into `filesDir/reports.json`
 * (`{"date/kind": {"hash", "text"}}`; text is the card's fallback when a check failed).
 * [force] drops today's entries first, so every card is written again.
 * Returns the Sync log line, or null when the writer is off, the model is missing or nothing changed. Blocks for seconds a card.
 */
internal suspend fun writeReports(ctx: Context, force: Boolean = false): String? = writing.withLock { writeLocked(ctx, force) }

private suspend fun writeLocked(ctx: Context, force: Boolean): String? {
    if (!ctx.reportWriter) return null
    runCatching { checkDownload(ctx, ctx.getSystemService(DownloadManager::class.java)) } // a download that ended while Settings was closed
    val model = reportModel(ctx)
    if (!model.exists()) return null
    val reply = Api.report(ctx, LocalDate.now()) as? JsonObject ?: return null
    val date = (reply["date"] as? JsonPrimitive)?.content ?: return null
    val file = File(ctx.filesDir, "reports.json")
    val reports = keepDays(runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap())), LocalDate.parse(date))
        .let { if (force) dropDay(it, date) else it }.toMutableMap()
    // Through a rename: a killed worker keeps what it wrote and the page never reads half a file.
    val save = { File(ctx.filesDir, "reports.json.tmp").apply { writeText(JsonObject(reports).toString()) }.renameTo(file) }
    if (force) save() // the page shows a placeholder for every card until its note lands
    val name = ctx.reportName
    val todo = toWrite(reports, date, (reply["cards"] as? JsonArray).orEmpty().map { it.jsonObject }, force)
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
            // at and advice (DT3): an update's `since` and `given` start from the morning card.
            reports["$date/$kind"] = JsonObject(mapOf("hash" to JsonPrimitive(reportHash(name, verdict, facts, advice)), "text" to JsonPrimitive(note ?: fallback),
                "at" to JsonPrimitive(System.currentTimeMillis() / 1000), "advice" to JsonArray(advice.map { JsonPrimitive(it) })))
            save() // after every card
        }
    }
    return String.format(Locale.ROOT, "Reports: wrote %d cards for %s in %.1f s, %d fallbacks",
        todo.size, date, (SystemClock.elapsedRealtime() - t0) / 1000.0, fallbacks)
}

/** DT3: [date]'s update entries (`date/update-<epoch s>`), oldest first. */
internal fun updates(reports: Map<String, JsonElement>, date: String): List<JsonObject> =
    reports.filterKeys { it.startsWith("$date/update-") }.values.map { it.jsonObject }.sortedBy { it.long("at") }

/** The last note's time: the latest update, else when the `today` card was written, else [date]'s start. */
internal fun updateSince(reports: Map<String, JsonElement>, date: String): Long =
    updates(reports, date).lastOrNull()?.long("at") ?: (reports["$date/today"] as? JsonObject)?.long("at")
        ?: LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

/** Advice lines already given on [date], exactly as the backend sent them: the `today` card's, then every update's. */
internal fun givenAdvice(reports: Map<String, JsonElement>, date: String): List<String> =
    ((reports["$date/today"] as? JsonObject)?.strings("advice").orEmpty() + updates(reports, date).flatMap { it.strings("advice") }).distinct()

internal fun updateEntry(at: Long, text: String, advice: List<String>, evening: Boolean) = JsonObject(mapOf(
    "at" to JsonPrimitive(at), "text" to JsonPrimitive(text), "advice" to JsonArray(advice.map { JsonPrimitive(it) }), "evening" to JsonPrimitive(evening)))

/** System instruction for an update note; [since] and [now] are `HH:mm`. */
internal fun updateSystem(name: String, since: String, now: String, evening: Boolean) = buildString {
    val who = name.trim().ifEmpty { "the user" }
    append("You write a short update to $who about their day so far, based on what their ring and phone measured. Write to them as \"you\".\n")
    append("It is $now. Say what changed since $since and what to do now")
    append(if (evening) ", to wind down for the night.\n" else ".\n")
    append("Length: 2 to 4 sentences in one paragraph. No lists, no headings, no markdown.\n")
    append("The earlier notes of today are given for context only: do not repeat them or their advice.\n")
    append("Use plain short sentences, no 'indicating' or 'suggesting'. Keep words like 'can', 'may' and 'likely' as they are.\n")
    append("Rules: use only the facts and advice given. When you use a number or a time, copy it exactly as written, in digits. ")
    append("Never mention a score. No medical advice, no illness names, nothing about medication. Do not invent advice. Give every advice line. ")
    append("If no advice is given, end with an observation, not a suggestion.")
}

/** User message for an update: today's earlier notes and advice as context, then what is new. */
internal fun updatePrompt(earlier: List<String>, given: List<String>, facts: List<String>, advice: List<String>) = buildString {
    if (earlier.isNotEmpty()) {
        append("Earlier notes today (context, do not repeat):\n")
        earlier.forEach { append("- $it\n") }
    }
    if (given.isNotEmpty()) {
        append("Advice already given today (do not repeat):\n")
        given.forEach { append("- $it\n") }
    }
    append("New since the last note:\n")
    facts.forEach { append("- $it\n") }
    if (advice.isNotEmpty()) {
        append("Advice to give now, in your own words:\n")
        advice.forEach { append("- $it\n") }
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(s: Long) = hhmm.format(Instant.ofEpochSecond(s).atZone(ZoneId.systemDefault()))

/**
 * DT3 ("Update me", and the automatic [evening] entry): `/report/update` from the last note, then one note stored as
 * `"date/update-<at>": {at, text, advice, evening}` in `reports.json` (text is the fallback when a check failed).
 * Returns the backend's reply (`nothing_new` true: nothing is written), or null when the writer is off, the model is
 * missing or the backend did not answer. Blocks for seconds.
 */
internal suspend fun writeUpdate(ctx: Context, evening: Boolean = false): JsonObject? = writing.withLock {
    if (!ctx.reportWriter) return@withLock null
    val model = reportModel(ctx)
    if (!model.exists()) return@withLock null
    val date = LocalDate.now().toString()
    val file = File(ctx.filesDir, "reports.json")
    val reports = keepDays(runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap())), LocalDate.parse(date)).toMutableMap()
    val since = updateSince(reports, date)
    val given = givenAdvice(reports, date)
    val reply = Api.reportUpdate(ctx, since, given) ?: return@withLock null
    if ((reply["nothing_new"] as? JsonPrimitive)?.content == "true") return@withLock reply
    val at = reply.long("at") ?: return@withLock null
    val facts = reply.strings("facts")
    val advice = reply.strings("advice")
    val times = listOf(clock(since), clock(at))
    val earlier = listOfNotNull((reports["$date/today"] as? JsonObject)?.str("text")) + updates(reports, date).map { it.str("text") }
    val cfg = ConversationConfig(systemInstruction = Contents.of(updateSystem(ctx.reportName, times[0], times[1], (reply["evening"] as? JsonPrimitive)?.content == "true")),
        samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.4))
    val note = Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU())).use { engine ->
        engine.initialize()
        // The two times are in the instruction, so they may appear in the note.
        engine.createConversation(cfg).use { checkedNote(it.sendMessage(updatePrompt(earlier, given, facts, advice)).toString(), facts + times, advice) }
    }
    reports["$date/update-$at"] = updateEntry(at, note ?: reply.str("fallback"), advice, evening)
    File(ctx.filesDir, "reports.json.tmp").apply { writeText(JsonObject(reports).toString()) }.renameTo(file)
    reply
}

private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
