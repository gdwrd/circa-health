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
import com.nsheremet.circa.Prefs.cloudKey
import com.nsheremet.circa.Prefs.cloudModel
import com.nsheremet.circa.Prefs.reportName
import com.nsheremet.circa.Prefs.reportProvider
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// AI5: Gemma on the phone turns each /report card into a note; reports.json keeps them for the Reports page.

internal val kindFocus = mapOf(
    "today" to "what stands out this morning",
    "yesterday" to "what stood out about yesterday",
    "night" to "what was unusual about the night",
    "tonight" to "what to do this evening and why",
    "body" to "the signal that stands out, said calmly",
    "week_overview" to "how last week went as a whole and the one thing to put first this week",
    "week_sleep" to "how last week's sleep went and what shaped it",
    "week_activity" to "how active last week was and how it was spread",
    "week_body" to "what the body signals did over last week, said calmly",
    "week_mind" to "how energy and mood went over last week",
    "week_effects" to "what seemed to affect what last week",
)

/** System instruction: role, length and rules for one card. */
internal fun reportSystem(name: String, kind: String) = buildString {
    val who = name.trim().ifEmpty { "the user" }
    append("You write a short personal note to $who about how their body is doing, based on what their ring measured. ")
    append("Write to them as \"you\"")
    append(if (kind == "today" && name.isNotBlank()) " and open with their name.\n" else ".\n")
    append("Length: ${if (kind.startsWith("week_")) "3 to 5" else "2 to 4"} sentences in one paragraph. No lists, no headings, no markdown.\n")
    append("Say each fact once, in plain words, with its reason when the fact gives one. Add nothing that is not in the facts ")
    append("and do not describe readings that are not given. Then give the advice.\n")
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

/**
 * DT1: the [cards] of [date]'s `/report` to write. A card with an entry is never rewritten; nothing is written until the
 * `night` card is in, unless [force].
 * RG1: [force] (Regenerate) also returns each of [date]'s card entries (never `update-*`) rebuilt from its stored inputs
 * with its `at`; an entry without inputs takes them from its `/report` card once.
 */
internal fun toWrite(reports: Map<String, JsonElement>, date: String, cards: List<JsonObject>, force: Boolean): List<JsonObject> {
    val fresh = if (!force && cards.none { it.str("kind") == "night" }) emptyList() else cards.filter { "$date/${it.str("kind")}" !in reports }
    if (!force) return fresh
    val byKind = cards.associateBy { it.str("kind") }
    return reports.filterKeys { it.startsWith("$date/") && !it.startsWith("$date/update-") }.mapNotNull { (k, e) ->
        val kind = k.substringAfter('/')
        val entry = e as? JsonObject ?: return@mapNotNull null
        val inputs = if ("facts" in entry) entry else byKind[kind] ?: return@mapNotNull null
        JsonObject(inputs + listOfNotNull("kind" to JsonPrimitive(kind), entry["at"]?.let { "at" to it }))
    } + fresh
}

/** FN2: a quiet card has nothing new; its fallback is the note and the model is not called. */
internal fun isQuiet(card: JsonObject) = (card["quiet"] as? JsonPrimitive)?.content == "true"

/** The first two sentences of [text]: a notification body. */
internal fun firstSentences(text: String): String = text.trim().split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")

/** CW3: the chosen writer can write: this phone has the model file, a cloud provider has a key and a model id. */
internal fun writerReady(ctx: Context): Boolean = ctx.reportProvider.let { p ->
    if (p == "phone") reportModel(ctx).exists() else ctx.cloudKey(p).isNotEmpty() && ctx.cloudModel(p).isNotEmpty()
}

/** CW3: runs [block] with the chosen writer's `(system, user) -> reply`: one LiteRT engine on the phone, else [cloudNote]. */
private suspend fun <T> withWriter(ctx: Context, block: suspend (suspend (String, String) -> String) -> T): T {
    val p = ctx.reportProvider
    if (p != "phone") return block { system, user -> cloudNote(p, ctx.cloudKey(p), ctx.cloudModel(p), system, user) }
    return Engine(EngineConfig(modelPath = reportModel(ctx).path, backend = Backend.CPU())).use { engine ->
        engine.initialize()
        block { system, user ->
            val cfg = ConversationConfig(systemInstruction = Contents.of(system), samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.4))
            engine.createConversation(cfg).use { it.sendMessage(user).toString() }
        }
    }
}

// One writer at a time: the Reports page and a sync may both start one; the second waits, then finds nothing to write.
private val writing = Mutex()

/**
 * Writes the notes of today's `/report` cards that have none yet ([toWrite]) into `filesDir/reports.json`
 * (`{"date/kind": {"hash", "text", "at", "advice", "verdict", "facts", "fallback", "quiet"}}`; text is the card's fallback
 * when a check failed). [force] blanks today's card texts first and rewrites them from the stored inputs, keeping `at`.
 * Returns the Sync log line, or null when the writer is off or not ready ([writerReady]) or nothing changed. Blocks for seconds a card.
 */
internal suspend fun writeReports(ctx: Context, force: Boolean = false): String? = writing.withLock { writeLocked(ctx, force) }

private suspend fun writeLocked(ctx: Context, force: Boolean): String? {
    if (!ctx.reportWriter) return null
    runCatching { checkDownload(ctx, ctx.getSystemService(DownloadManager::class.java)) } // a download that ended while Settings was closed
    if (!writerReady(ctx)) return null
    val file = File(ctx.filesDir, "reports.json")
    val stored = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    val now = LocalDate.now()
    val reply = Api.report(ctx, now, dayAdvice(stored, now.minusDays(1).toString())) as? JsonObject ?: return null
    val date = (reply["date"] as? JsonPrimitive)?.content ?: return null
    val reports = keepDays(stored, LocalDate.parse(date)).toMutableMap()
    val todo = toWrite(reports, date, (reply["cards"] as? JsonArray).orEmpty().map { it.jsonObject }, force)
    return writeCards(ctx, file, reports, date, todo, force, "Reports")
}

/** LW4: the Monday that starts the last finished Mon to Sun week before [today]. */
internal fun lastWeekStart(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)

/** LW4: the week [start]'s [cards] to write: all of them once, never again unless [force] (then RG1's rule, [toWrite]). */
internal fun weekToWrite(weeks: Map<String, JsonElement>, start: String, cards: List<JsonObject>, force: Boolean): List<JsonObject> = when {
    force -> toWrite(weeks, start, cards, true)
    weeks.keys.any { it.startsWith("$start/") } -> emptyList()
    else -> cards
}

/**
 * LW4: writes the last finished week's `/report/week` cards into `filesDir/weeks.json` (`"<start>/<kind>"`, the shape of
 * a `reports.json` card entry), never pruned. A sync writes a week once; [force] (Regenerate) rewrites its texts from
 * the stored inputs, keeping `at`. Returns the Sync log line, or null when nothing changed.
 */
internal suspend fun writeWeek(ctx: Context, force: Boolean = false): String? = writing.withLock {
    if (!ctx.reportWriter) return@withLock null
    if (!writerReady(ctx)) return@withLock null
    val file = File(ctx.filesDir, "weeks.json")
    val weeks = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap())).toMutableMap()
    // Battery: the week is stored already, no need to ask the backend.
    if (!force && weeks.keys.any { it.startsWith("${lastWeekStart(LocalDate.now())}/") }) return@withLock null
    val reply = Api.reportWeek(ctx) ?: return@withLock null
    val start = (reply["start"] as? JsonPrimitive)?.content ?: return@withLock null
    val todo = weekToWrite(weeks, start, (reply["cards"] as? JsonArray).orEmpty().map { it.jsonObject }, force)
    writeCards(ctx, file, weeks, start, todo, force, "Week")
}

/**
 * Writes each card of [todo] into [reports] as `"<date>/<kind>"` ([date] is a week's start for `weeks.json`) and saves
 * [file] after every card. [force] blanks those texts first. Returns the Sync log line headed [label], or null when
 * [todo] is empty.
 */
private suspend fun writeCards(ctx: Context, file: File, reports: MutableMap<String, JsonElement>, date: String,
                               todo: List<JsonObject>, force: Boolean, label: String): String? {
    if (todo.isEmpty()) return null
    val name = ctx.reportName
    // Through a rename: a killed worker keeps what it wrote and the page never reads half a file.
    val save = { File(file.path + ".tmp").apply { writeText(JsonObject(reports).toString()) }.renameTo(file); Unit }
    val old = reports.toMap()
    if (force) { // blank text: the page shows a placeholder until the note lands; `at` and the rest stay
        todo.forEach { c -> (reports["$date/${c.str("kind")}"] as? JsonObject)?.let { reports["$date/${c.str("kind")}"] = JsonObject(it + ("text" to JsonPrimitive(""))) } }
        save()
    }
    val t0 = SystemClock.elapsedRealtime()
    val (quiet, loud) = todo.partition(::isQuiet)
    quiet.forEach { reports["$date/${it.str("kind")}"] = cardEntry(name, it, null); save() }
    val (fallbacks, failed) = if (loud.isEmpty()) 0 to emptyList() else withWriter(ctx) { noteCards(name, date, loud, reports, old, it, save) }
    return String.format(Locale.ROOT, "%s: wrote %d cards for %s in %.1f s, %d fallbacks, %d quiet",
        label, todo.size - failed.size, date, (SystemClock.elapsedRealtime() - t0) / 1000.0, fallbacks, quiet.size) +
        if (failed.isEmpty()) "" else ", ${failed.size} failed: ${failed.first()}"
}

/**
 * Card [c]'s entry; [note] null: the fallback. at and advice (DT3): an update's `since` and `given` start from the morning
 * card; a rewrite keeps the card's `at`. The inputs (RG1) let Regenerate rewrite the same card later.
 */
private fun cardEntry(name: String, c: JsonObject, note: String?): JsonObject {
    val verdict = (c["verdict"] as? JsonPrimitive)?.contentOrNull
    val facts = c.strings("facts")
    val advice = c.strings("advice")
    return JsonObject(mapOf("hash" to JsonPrimitive(reportHash(name, verdict, facts, advice)),
        "text" to JsonPrimitive(note ?: c.str("fallback")), "at" to JsonPrimitive(c.long("at") ?: (System.currentTimeMillis() / 1000)),
        "advice" to JsonArray(advice.map { JsonPrimitive(it) }), "verdict" to JsonPrimitive(verdict), "facts" to JsonArray(facts.map { JsonPrimitive(it) }),
        "fallback" to JsonPrimitive(c.str("fallback")), "quiet" to JsonPrimitive(isQuiet(c))))
}

/**
 * CW3: writes each of [loud] into [reports] from [note] through [checkedNote] (the fallback when a check fails), calling
 * [save] after every card. A card whose [note] fails ([cloudNote]'s IllegalStateException; LiteRT's own exceptions end
 * the write as before) stores nothing, so the next sync tries again; a forced rewrite gets its [old] entry back.
 * Returns the fallback count and the failures' short reasons.
 */
internal suspend fun noteCards(name: String, date: String, loud: List<JsonObject>, reports: MutableMap<String, JsonElement>,
                               old: Map<String, JsonElement>, note: suspend (String, String) -> String, save: () -> Unit): Pair<Int, List<String>> {
    var fallbacks = 0
    val failed = mutableListOf<String>()
    for (c in loud) {
        val key = "$date/${c.str("kind")}"
        val facts = c.strings("facts")
        val advice = c.strings("advice")
        val reply = try {
            note(reportSystem(name, c.str("kind")), reportPrompt((c["verdict"] as? JsonPrimitive)?.contentOrNull, facts, advice, c.str("fallback")))
        } catch (e: IllegalStateException) {
            failed += e.message.orEmpty()
            old[key]?.let { reports[key] = it; save() }
            continue
        }
        val checked = checkedNote(reply, facts, advice)
        if (checked == null) fallbacks++
        reports[key] = cardEntry(name, c, checked)
        save() // after every card
    }
    return fallbacks to failed
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

/** FN2: every advice line stored under [date] (all cards and updates), distinct: `/report`'s `given` for the next day. */
internal fun dayAdvice(reports: Map<String, JsonElement>, date: String): List<String> =
    reports.filterKeys { it.startsWith("$date/") }.values.flatMap { (it as? JsonObject)?.strings("advice").orEmpty() }.distinct()

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
 * Returns the backend's reply (`nothing_new` true: nothing is written), or null when the writer is off or not ready,
 * the backend did not answer or the cloud call failed. Blocks for seconds.
 */
internal suspend fun writeUpdate(ctx: Context, evening: Boolean = false): JsonObject? = writing.withLock {
    if (!ctx.reportWriter) return@withLock null
    if (!writerReady(ctx)) return@withLock null
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
    val system = updateSystem(ctx.reportName, times[0], times[1], (reply["evening"] as? JsonPrimitive)?.content == "true")
    val note = try {
        // The two times are in the instruction, so they may appear in the note.
        withWriter(ctx) { checkedNote(it(system, updatePrompt(earlier, given, facts, advice)), facts + times, advice) }
    } catch (e: IllegalStateException) {
        return@withLock null // a cloud failure stores nothing: the next try writes it
    }
    reports["$date/update-$at"] = updateEntry(at, note ?: reply.str("fallback"), advice, evening)
    File(ctx.filesDir, "reports.json.tmp").apply { writeText(JsonObject(reports).toString()) }.renameTo(file)
    reply
}

private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
