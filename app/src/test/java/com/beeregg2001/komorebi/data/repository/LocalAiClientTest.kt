package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.model.AiSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocalAiClientTest {
    private lateinit var server: MockWebServer
    private val client = LocalAiClient()
    private val wav = ByteArray(100).apply {
        "RIFF".toByteArray().copyInto(this)
        "WAVE".toByteArray().copyInto(this, 8)
    }
    private fun settings() = AiSettings("LOCAL", server.url("/v1").toString(), "test-qwen",
        "chat-token", server.url("/").toString(), "whisper-local", "speech-token")
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun transcriptionUsesMultipartAndSeparateToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"BS11にして"}"""))
        assertEquals("BS11にして", client.transcribe(settings(), wav))
        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer speech-token", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"voice.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
        val languagePart = body.split("\r\n--").first { it.contains("name=\"language\"") }
        assertEquals("ja", languagePart.substringAfter("\r\n\r\n").trim())
        assertTrue(body.contains("whisper-local"))
        assertTrue(body.contains("response_format"))
    }

    @Test fun chatUsesConfiguredModelAndOnlyAssistantContent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"[PLAY_LIVE: bs11]","reasoning_content":"[PLAY_LIVE: wrong]"}}]}"""))
        assertEquals("[PLAY_LIVE: bs11]", client.chat(settings(), "system", "user"))
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer chat-token", request.getHeader("Authorization"))
        val json = Gson().fromJson(request.body.readUtf8(), JsonObject::class.java)
        assertEquals("test-qwen", json["model"].asString)
        assertFalse(json["stream"].asBoolean)
        assertEquals("system", json["messages"].asJsonArray[0].asJsonObject["role"].asString)
        assertFalse(json["chat_template_kwargs"].asJsonObject["enable_thinking"].asBoolean)
    }

    @Test fun emptyTranscriptionStaysEmpty() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"  "}"""))
        assertEquals("", client.transcribe(settings().copy(whisperApiKey = ""), wav))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun missingTranscriptIsAnError() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":"wrong schema"}"""))
        assertTrue(runCatching { client.transcribe(settings(), wav) }.isFailure)
    }

    @Test fun httpFailureDoesNotFallBackOrRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching { client.chat(settings(), "system", "user") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("401"))
        assertEquals(1, server.requestCount)
    }

    @Test fun truncatedCommandsAreRejected() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"[PLAY_LIVE: bs11"}}]}"""))
        assertTrue(runCatching { client.chat(settings(), "system", "user") }.isFailure)
    }

    @Test fun invalidAudioIsNotUploaded() = runBlocking {
        assertTrue(runCatching { client.transcribe(settings(), ByteArray(44)) }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun baseUrlPreservesProxyPrefixAndAvoidsDuplicateV1() {
        assertEquals("https://host/proxy/v1/chat/completions", LocalAiClient.endpoint("https://host/proxy/v1/", "chat/completions").toString())
        assertEquals("http://host:8000/v1/audio/transcriptions", LocalAiClient.endpoint("http://host:8000", "audio/transcriptions").toString())
        assertTrue(runCatching { LocalAiClient.endpoint("http://user:secret@host", "models") }.isFailure)
    }

    @Test fun thinkingCommandsAreNeverExposed() {
        assertEquals("こんにちは", LocalAiClient.cleanAssistantText("<think>\n[PLAY_LIVE: wrong]\n</think>こんにちは"))
        assertEquals("", LocalAiClient.cleanAssistantText("<think>[PLAY_LIVE: wrong]"))
    }
}
