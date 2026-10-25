package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class EpgStationRuleCancellationTest {
    private lateinit var server: MockWebServer
    private lateinit var cancellation: EpgStationRuleCancellation
    private var clock = 1_800_000_000_000L
    private var start = clock + 3_600_000L
    private var exists = true
    private var relatedExists = true
    private var activeRecording = false
    private var deleteStatus = 200
    private var keepReservations = false
    private var detailStatus = 200
    private var incompleteReservations = false
    private var deletes = 0
    private var unexpectedMutations = 0
    private var extraRuleField = ""
    private var name = "イッテＱ"
    private var redirect: String? = null

    private fun rule(id: Int = 7) = """{"id":$id,"isTimeSpecification":false,"searchOption":{"keyword":"$name","name":true,"channelIds":[101],"times":[{"week":127}]},"reserveOption":{"enable":true}$extraRuleField}"""
    private fun reserve(id: Int, ruleId: Int) = """{"id":$id,"ruleId":$ruleId,"programId":$id,"channelId":101,"name":"対象番組","startAt":$start,"endAt":${start + 1_800_000}}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path in listOf("/api/recording", "/api/reserves") && request.requestUrl!!.queryParameter("isHalfWidth") != "false") {
                    return json("""{"error":"isHalfWidth is required"}""").setResponseCode(400)
                }
                if (request.method == "DELETE" && path == "/api/rules/7") {
                    deletes++
                    if (deleteStatus == 200) { exists = false; if (!keepReservations) relatedExists = false }
                    return json("""{"code":$deleteStatus}""").setResponseCode(deleteStatus)
                }
                if (request.method != "GET") { unexpectedMutations++; return MockResponse().setResponseCode(400) }
                return when (path) {
                    "/api/rules/7" -> when {
                        redirect != null -> MockResponse().setResponseCode(302).setHeader("Location", redirect!!)
                        detailStatus != 200 -> MockResponse().setResponseCode(detailStatus)
                        !exists -> MockResponse().setResponseCode(404)
                        else -> json(rule())
                    }
                    "/api/rules" -> json("""{"rules":[${rule()},${rule(8)}],"total":2}""")
                    "/api/channels" -> json("""[{"id":101,"name":"テスト放送局"}]""")
                    "/api/reserves" -> if (incompleteReservations) json("""{"reserves":[],"total":2}""") else {
                        val entries = listOfNotNull(if (relatedExists) reserve(100, 7) else null, reserve(200, 8))
                        json("""{"reserves":[${entries.joinToString()}],"total":${entries.size}}""")
                    }
                    "/api/recording" -> if (activeRecording) json("""{"records":[{"id":900,"ruleId":7,"isRecording":true}],"total":1}""")
                        else json("""{"records":[],"total":0}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(GsonConverterFactory.create()).build().create(EpgStationApi::class.java)
        cancellation = EpgStationRuleCancellation(api) { clock }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun matchingOnlyPreparesCandidatesAndKeepsSameNameRulesSeparate() = runBlocking {
        val candidates = cancellation.candidates("イッテQ").getOrThrow()
        assertEquals(listOf(7, 8), candidates.map { it.id })
        assertEquals(0, deletes)
    }

    @Test fun previewDoesNotWriteAndIncludesOnlySelectedRuleReservations() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        assertEquals(listOf(100), preview.programs.map { it.id })
        assertEquals(0, deletes)
        assertEquals(0, unexpectedMutations)
    }

    @Test fun confirmsRuleAndReservationRemovalWithoutTouchingRecordedFilesOrOtherRules() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        assertTrue(cancellation.confirm(preview.token).isSuccess)
        assertEquals(1, deletes)
        assertEquals(0, unexpectedMutations)
        assertFalse(exists)
    }

    @Test fun inventedTokenCannotDelete() = runBlocking {
        assertTrue(cancellation.confirm("invented-token").isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun confirmationCannotBeReused() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        assertTrue(cancellation.confirm(preview.token).isSuccess)
        assertTrue(cancellation.confirm(preview.token).isFailure)
        assertEquals(1, deletes)
    }

    @Test fun newerPreviewInvalidatesOldConfirmation() = runBlocking {
        val old = cancellation.preview(7).getOrThrow()
        cancellation.preview(7).getOrThrow()
        assertTrue(cancellation.confirm(old.token).isFailure)
        assertEquals(0, deletes)
    }

    @Test fun expiredConfirmationCannotDelete() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        clock += 120_001
        assertTrue(cancellation.confirm(preview.token).isFailure)
        assertEquals(0, deletes)
    }

    @Test fun activeRecordingBlocksPreview() = runBlocking {
        activeRecording = true
        assertTrue(cancellation.preview(7).exceptionOrNull()!!.message!!.contains("録画中"))
        assertEquals(0, deletes)
    }

    @Test fun recordingStartingAfterPreviewBlocksDeletion() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        activeRecording = true
        assertTrue(cancellation.confirm(preview.token).isFailure)
        assertEquals(0, deletes)
    }

    @Test fun imminentRecordingIsProtected() = runBlocking {
        start = clock + 600_000
        assertTrue(cancellation.preview(7).isFailure)
        assertEquals(0, deletes)
    }

    @Test fun modifiedRuleIncludingUnknownFieldsRequiresAnotherConfirmation() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        extraRuleField = ",\"futureServerSetting\":true"
        assertTrue(cancellation.confirm(preview.token).isFailure)
        assertEquals(0, deletes)
    }

    @Test fun scheduleChangeRequiresAnotherConfirmation() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        start += 30 * 60_000
        assertTrue(cancellation.confirm(preview.token).isFailure)
        assertEquals(0, deletes)
    }

    @Test fun incompleteReservationListNeverAllowsDeletion() = runBlocking {
        incompleteReservations = true
        assertTrue(cancellation.preview(7).isFailure)
        assertEquals(0, deletes)
    }

    @Test fun unauthorizedPreflightDoesNotWrite() = runBlocking {
        detailStatus = 403
        assertTrue(cancellation.preview(7).exceptionOrNull()!!.message!!.contains("権限"))
        assertEquals(0, deletes)
    }

    @Test fun failedDeleteIsNotReportedAsSuccessAndIsNeverRetried() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        deleteStatus = 500
        assertTrue(cancellation.confirm(preview.token).isFailure)
        assertTrue(cancellation.confirm(preview.token).isFailure)
        assertEquals(1, deletes)
    }

    @Test fun pendingRelatedReservationsDoNotReportFalseSuccess() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        keepReservations = true
        assertTrue(cancellation.confirm(preview.token).exceptionOrNull()!!.message!!.contains("完了を確認できません"))
        assertEquals(1, deletes)
    }

    @Test fun switchedServerCannotReceiveDeletion() = runBlocking {
        val preview = cancellation.preview(7).getOrThrow()
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(json(rule()))
            redirect = other.url("/api/rules/7").toString()
            assertTrue(cancellation.confirm(preview.token).exceptionOrNull()!!.message!!.contains("接続先"))
            assertEquals(0, deletes)
            assertEquals("GET", other.takeRequest().method)
        } finally { other.shutdown() }
    }
}
