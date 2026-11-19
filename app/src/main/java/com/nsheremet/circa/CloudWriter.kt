package com.nsheremet.circa

import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class CloudModel(val id: String, val name: String, val free: Boolean)

private val timeout = ConnectionTimeoutConfig(60_000, 60_000, 60_000)
private val factory by lazy { KtorKoogHttpClient.Factory(HttpClient(OkHttp)) }
private val http by lazy { OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build() }

/** One note from [provider] ("openrouter", "anthropic", "openai"); throws with a one-line message on any failure or a blank reply. */
suspend fun cloudNote(provider: String, key: String, model: String, system: String, user: String): String = short(key) {
    val m = when (provider) {
        "openrouter" -> LLModel(LLMProvider.OpenRouter, model, listOf(LLMCapability.Completion))
        "openai" -> LLModel(LLMProvider.OpenAI, model, listOf(LLMCapability.Completion, LLMCapability.OpenAIEndpoint.Completions))
        "anthropic" -> LLModel(LLMProvider.Anthropic, model, listOf(LLMCapability.Completion, LLMCapability.Tools))
        else -> error("Unknown provider $provider")
    }
    val c = client(provider, key, m)
    val text = c.execute(prompt("note") { system(system); user(user) }, m).textContent().trim()
    text.ifEmpty { error("Empty reply (refused?)") }
}

/** The models [provider] offers, sorted by name; OpenRouter's list is public, [key] is unused there. */
suspend fun cloudModels(provider: String, key: String): List<CloudModel> = short(key) {
    when (provider) {
        "openrouter" -> withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("https://openrouter.ai/api/v1/models").build()).execute().use { r ->
                val body = r.body.string()
                if (!r.isSuccessful) error(shortReason(r.code, body))
                openRouterModels(body)
            }
        }
        "openai" -> client(provider, key, null).models().map { it.id }.filter(::openAiChat)
            .map { CloudModel(it, it, false) }.sortedBy { it.name.lowercase() }
        "anthropic" -> client(provider, key, null).models()
            .map { CloudModel(it.id, it.id, false) }.sortedBy { it.name.lowercase() }
        else -> error("Unknown provider $provider")
    }
}

private fun client(provider: String, key: String, m: LLModel?): LLMClient = when (provider) {
    "openrouter" -> OpenRouterLLMClient(key, OpenRouterClientSettings(timeoutConfig = timeout), factory)
    "openai" -> OpenAILLMClient(key, OpenAIClientSettings(timeoutConfig = timeout), factory)
    else -> AnthropicLLMClient(key, AnthropicClientSettings(modelVersionsMap = m?.let { mapOf(it to it.id) } ?: emptyMap(), timeoutConfig = timeout), factory)
}

/** Runs [block], rethrowing any failure as an exception whose message is one short line without [key]. */
private suspend fun <T> short(key: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    val h = generateSequence(e) { it.cause }.filterIsInstance<KoogHttpClientException>().firstOrNull()
    val line = e.message?.lineSequence()?.firstOrNull().orEmpty()
    val msg = if (h?.statusCode != null) shortReason(h.statusCode!!, h.errorBody)
        else (if (e is IllegalStateException) line else "${e::class.simpleName}: $line").take(120)
    throw IllegalStateException(if (key.isNotEmpty()) msg.replace(key, "…") else msg)
}

/** "401 Invalid API key": the status and the provider's error.message, else the body's first line. */
internal fun shortReason(status: Int, body: String?): String {
    val detail = runCatching {
        val err = Json.parseToJsonElement(body!!).jsonObject["error"]
        ((err as? JsonObject)?.get("message") ?: err)!!.jsonPrimitive.contentOrNull
    }.getOrNull() ?: body?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()
    return "$status $detail".trim().take(120)
}

/** OpenRouter's /models body: free when both prompt and completion cost "0". */
internal fun openRouterModels(body: String): List<CloudModel> =
    Json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.map { it.jsonObject }.map { m ->
        val id = m["id"]!!.jsonPrimitive.content
        val price = m["pricing"] as? JsonObject
        fun zero(k: String) = price?.get(k)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() == 0.0
        CloudModel(id, (m["name"] as? JsonPrimitive)?.contentOrNull ?: id, zero("prompt") && zero("completion"))
    }.sortedBy { it.name.lowercase() }

private val notChat = listOf("embedding", "audio", "tts", "image", "realtime", "transcribe", "moderation")

/** OpenAI's model list mixes chat, image, audio and embedding models; keep the chat ones. */
internal fun openAiChat(id: String): Boolean =
    (id.startsWith("gpt-") || id.startsWith("chatgpt-") || Regex("^o\\d").containsMatchIn(id)) && notChat.none { it in id }
