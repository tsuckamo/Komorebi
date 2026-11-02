package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.model.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class EpgStationRecordingPresetsTest {
    private lateinit var server: MockWebServer
    private lateinit var presets: EpgStationRecordingPresets
    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        presets = EpgStationRecordingPresets(Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(EpgStationApi::class.java))
    }
    @After fun tearDown() { server.shutdown() }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun defaultPreset(deleteOriginal: Boolean = false) = """{"id":1,"name":"圧縮用","isDefault":true,"settings":{"parentDirectoryName":"archive","mode1":"HEVC test","encodeParentDirectoryName1":"archive","directory1":"compressed","isDeleteOriginalAfterEncode":$deleteOriginal,"priority":3,"conflictPolicy":"STRICT","allowEndLack":false,"startMarginSec":15,"endMarginSec":10,"tags":[4],"finishCommandName":"post-process"}}"""

    @Test fun manualReservationSendsServerDefaultIncludingMetadataEncoderAndDestination() = runBlocking {
        server.enqueue(json(defaultPreset()))
        server.enqueue(json("""{"encode":["HEVC test","H264 test"]}"""))
        server.enqueue(json("{}").setResponseCode(201))
        presets.addManual(900, false)
        assertEquals("/api/recording-presets/default", server.takeRequest().path)
        assertEquals("/api/config", server.takeRequest().path)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/reserves", post.path)
        val body = JsonParser().parse(post.body.readUtf8()).asJsonObject
        assertEquals(900L, body["programId"].asLong)
        assertFalse(body["allowEndLack"].asBoolean)
        assertEquals("archive", body.getAsJsonObject("saveOption")["parentDirectoryName"].asString)
        val encode = body.getAsJsonObject("encodeOption")
        assertEquals("HEVC test", encode["mode1"].asString)
        assertEquals("compressed", encode["directory1"].asString)
        assertFalse(encode["isDeleteOriginalAfterEncode"].asBoolean)
        assertEquals(15, body["startMarginSec"].asInt)
        assertEquals("post-process", body["finishCommandName"].asString)
    }

    @Test fun automaticRulesInheritAllEncoderSlotsAndOriginalDeletionOnlyWhenSelected() {
        val preset = EsRecordingPreset(1, "圧縮", true, EsRecordingPresetSettings(
            parentDirectoryName = "archive", mode1 = "HEVC test", mode2 = "mobile", mode3 = "backup",
            encodeParentDirectoryName2 = "other", directory3 = "copies", isDeleteOriginalAfterEncode = true,
            allowEndLack = false, priority = 2, tags = listOf(4), finishCommandName = "finish"))
        val body = presets.rule(EsAddRuleOption(searchOption = EsRuleSearchOption(keyword = "番組")), preset)
        assertEquals("番組", body.searchOption.keyword)
        assertEquals("archive", body.saveOption!!.parentDirectoryName)
        assertEquals("backup", body.encodeOption!!.mode3)
        assertEquals("other", body.encodeOption.encodeParentDirectoryName2)
        assertTrue(body.encodeOption.isDeleteOriginalAfterEncode)
        assertFalse(body.reserveOption.allowEndLack)
        assertEquals(2, body.reserveOption.priority)
        assertEquals(listOf(4), body.reserveOption.tags)
        assertEquals("finish", body.reserveOption.finishCommandName)
    }

    @Test fun unavailableEncoderCannotSilentlyCreateRawTsReservation() = runBlocking {
        server.enqueue(json(defaultPreset()))
        server.enqueue(json("""{"encode":["another encoder"]}"""))
        assertTrue(runCatching { presets.addManual(900, true) }.isFailure)
        assertEquals(2, server.requestCount)
    }
    @Test fun serverErrorCannotSilentlyCreateRawTsReservation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(runCatching { presets.addManual(900, true) }.isFailure)
        assertEquals(1, server.requestCount)
    }
    @Test fun malformedPresetCannotSilentlyCreateRawTsReservation() = runBlocking {
        server.enqueue(json("{}"))
        assertTrue(runCatching { presets.addManual(900, true) }.isFailure)
        assertEquals(1, server.requestCount)
    }
    @Test fun olderServersAndUnselectedDefaultsRemainSupported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(presets.loadDefault())
        server.enqueue(json("null"))
        assertNull(presets.loadDefault())
    }
    @Test fun presetWithoutEncodingRemainsRawAndDoesNotInventHevc() = runBlocking {
        server.enqueue(json("""{"id":1,"name":"raw","isDefault":true,"settings":{"parentDirectoryName":"archive"}}"""))
        val preset = presets.loadDefault()
        assertNull(presets.manual(EsManualReserveOption(900), preset).encodeOption)
        assertEquals(1, server.requestCount)
    }
}
