package com.nsheremet.circa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudWriterTest {
    @Test fun openAiKeepsChatModels() {
        listOf("gpt-4o", "gpt-4.1-mini", "gpt-5", "o3", "o4-mini", "o1-preview", "chatgpt-4o-latest").forEach { assertTrue(it, openAiChat(it)) }
    }

    @Test fun openAiDropsTheRest() {
        listOf(
            "text-embedding-3-small", "gpt-4o-audio-preview", "gpt-4o-mini-tts", "gpt-image-1", "gpt-4o-realtime-preview",
            "gpt-4o-transcribe", "omni-moderation-latest", "dall-e-3", "whisper-1", "tts-1", "babbage-002", "omni-search",
        ).forEach { assertFalse(it, openAiChat(it)) }
    }

    @Test fun openRouterFreeFlagAndOrder() {
        val body = """{"data":[
            {"id":"z/paid","name":"Zeta Paid","pricing":{"prompt":"0.000001","completion":"0.000002"}},
            {"id":"a/free:free","name":"alpha (free)","pricing":{"prompt":"0","completion":"0","request":"0"}},
            {"id":"m/half","name":"Mid Half","pricing":{"prompt":"0","completion":"0.00001"}},
            {"id":"n/noprice","name":"Beta"}
        ]}"""
        assertEquals(
            listOf(
                CloudModel("a/free:free", "alpha (free)", true),
                CloudModel("n/noprice", "Beta", false),
                CloudModel("m/half", "Mid Half", false),
                CloudModel("z/paid", "Zeta Paid", false),
            ),
            openRouterModels(body),
        )
    }

    @Test fun shortReasons() {
        assertEquals("401 Invalid API key", shortReason(401, """{"error":{"message":"Invalid API key","code":401}}"""))
        assertEquals("401 invalid x-api-key", shortReason(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        assertEquals("429 Rate limited", shortReason(429, """{"error":"Rate limited"}"""))
        assertEquals("502 Bad gateway", shortReason(502, "Bad gateway\n<html>"))
        assertEquals("500", shortReason(500, null))
    }
}
