package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.*
import com.beeregg2001.komorebi.data.model.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class EpgStationRuleCreationTest {
    private lateinit var server: MockWebServer
    private lateinit var service: EpgStationRuleCreation
    private var clock = 1_800_000_000_000L
    private var start = clock + 10_800_000
    private var title = "テスト番組 第1話"
    private var posts = 0
    private var otherWrites = 0
    private var saved = ""
    private var duplicate = false
    private var incomplete = false
    private var matches = true
    private var extraMatch = false
    private var reflect = true
    private var conflict = false
    private var status = 201
    private var savedWrong = false
    private var redirected = false
    private var detailStatus = 200
    private fun program(id: Long = 900) = """{"id":$id,"channelId":101,"startAt":$start,"endAt":${start+1_800_000},"name":"$title"}"""
    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val fullPath = request.requestUrl!!.encodedPath
                val path = "/api/" + fullPath.substringAfter("/api/")
                if (request.method == "POST" && path == "/api/rules") {
                    posts++
                    saved = request.body.readUtf8()
                    return json("""{"ruleId":7}""").setResponseCode(status).setHeader("Retry-After", "0")
                }
                if (request.method == "POST" && path == "/api/schedules/search") {
                    val body = JsonParser().parse(request.body.readUtf8()).asJsonObject
                    assertFalse(body["isHalfWidth"].asBoolean)
                    assertEquals(500, body["limit"].asInt)
                    return json(if (!matches) "[]" else if (extraMatch) "[${program()},${program(901)}]" else "[${program()}]")
                }
                if (request.method != "GET") { otherWrites++; return MockResponse().setResponseCode(400) }
                return when (path) {
                    "/api/schedules" -> json("""[{"channel":{"id":101,"networkId":1,"serviceId":1,"channelType":"GR","name":"テスト局"},"programs":[${program()}]}]""")
                    "/api/schedules/detail/900" -> when {
                        detailStatus != 200 -> MockResponse().setResponseCode(detailStatus)
                        redirected && !fullPath.startsWith("/changed/") -> MockResponse().setResponseCode(302).setHeader("Location", server.url("/changed/api/schedules/detail/900"))
                        else -> json(program())
                    }
                    "/api/rules" -> if (incomplete) json("""{"rules":[],"total":1}""") else if (duplicate)
                        json("""{"rules":[{"id":6,"isTimeSpecification":false,"searchOption":{"keyword":"テスト番組","channelIds":[101]},"reserveOption":{"enable":false}}],"total":1}""")
                        else json("""{"rules":[],"total":0}""")
                    "/api/rules/7" -> {
                        val body = JsonParser().parse(saved).asJsonObject
                        body.addProperty("id", 7)
                        if (!body.getAsJsonObject("searchOption").has("ignoreKeyRegExp")) body.getAsJsonObject("searchOption").addProperty("ignoreKeyRegExp", false)
                        if (savedWrong) body.getAsJsonObject("searchOption").addProperty("keyword", "別番組")
                        json(body.toString())
                    }
                    "/api/reserves" -> if (!reflect) json("""{"reserves":[],"total":0}""") else
                        json("""{"reserves":[{"id":12,"ruleId":7,"programId":900,"channelId":101,"name":"$title","startAt":$start,"endAt":${start+1_800_000},"isSkip":false,"isConflict":$conflict}],"total":1}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(EpgStationRuleWriteGuard()).build()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(EpgStationApi::class.java)
        service = EpgStationRuleCreation(api) { clock }
    }
    @After fun tearDown() { server.shutdown() }
    private suspend fun prepare(allDays: Boolean = false): AutoReserveCreatePreview {
        val candidate = service.candidates("テスト番組").getOrThrow().single()
        return service.preview(candidate.programId, AutoReserveCreateOptions(candidate.keyword, if (allDays) 127 else candidate.week)).getOrThrow()
    }
    @Test fun searchingAndPreviewingNeverCreateRules() = runBlocking {
        val p = prepare()
        assertEquals(listOf(900L), p.programs.map { it.programId })
        assertEquals(0, posts); assertEquals(0, otherWrites)
    }
    @Test fun createsOnlyConfirmedChannelAndDayWithoutFixedTime() = runBlocking {
        val p = prepare()
        assertTrue(service.confirm(p.token).getOrThrow().message.contains("予約反映を確認"))
        val body = JsonParser().parse(saved).asJsonObject
        assertFalse(body["isTimeSpecification"].asBoolean)
        val search = body.getAsJsonObject("searchOption")
        assertEquals("[101]", search["channelIds"].toString())
        val time = search.getAsJsonArray("times")[0].asJsonObject
        assertEquals(p.options.week, time["week"].asInt)
        assertFalse(time.has("start")); assertFalse(time.has("range"))
        assertTrue(search["name"].asBoolean); assertFalse(search["description"].asBoolean)
        assertTrue(search["ignoreKeyRegExp"].asBoolean)
        assertEquals(1, posts); assertEquals(0, otherWrites)
    }
    @Test fun allDaysMustBeChosenExplicitly() = runBlocking {
        val p = prepare(true)
        service.confirm(p.token).getOrThrow()
        assertTrue(saved.contains("\"week\":127"))
    }
    @Test fun serverCanNormalizeOmittedFalseOptions() = runBlocking {
        val c = service.candidates("テスト番組").getOrThrow().single()
        val p = service.preview(c.programId, AutoReserveCreateOptions(c.keyword, c.week, false)).getOrThrow()
        assertTrue(service.confirm(p.token).isSuccess)
    }
    @Test fun rejectsInventedSelectionAndConfirmation() = runBlocking {
        assertTrue(service.preview(999, AutoReserveCreateOptions("番組", 127)).isFailure)
        assertTrue(service.confirm("made-up").isFailure); assertEquals(0, posts)
    }
    @Test fun tokenCannotBeReused() = runBlocking {
        val p = prepare(); service.confirm(p.token).getOrThrow()
        assertTrue(service.confirm(p.token).isFailure); assertEquals(1, posts)
    }
    @Test fun expiredAndReplacedDraftsCannotWrite() = runBlocking {
        val old = prepare(); val fresh = prepare()
        assertTrue(service.confirm(old.token).isFailure)
        clock += 120_001
        assertTrue(service.confirm(fresh.token).isFailure); assertEquals(0, posts)
    }
    @Test fun duplicateEvenDisabledRuleStopsRegistration() = runBlocking {
        duplicate = true
        val candidate = service.candidates("テスト番組").getOrThrow().single()
        assertTrue(service.preview(900, AutoReserveCreateOptions(candidate.keyword, candidate.week)).isFailure)
        assertEquals(0, posts)
    }
    @Test fun newlyAddedDuplicateStopsConfirmation() = runBlocking {
        val p = prepare(); duplicate = true
        assertTrue(service.confirm(p.token).isFailure); assertEquals(0, posts)
    }
    @Test fun changedProgramAndSearchResultsStopConfirmation() = runBlocking {
        val p = prepare(); title = "変更された番組"
        assertTrue(service.confirm(p.token).isFailure)
        title = "テスト番組 第1話"
        val next = prepare(); extraMatch = true
        assertTrue(service.confirm(next.token).isFailure); assertEquals(0, posts)
    }
    @Test fun incompleteRuleListStopsConfirmation() = runBlocking {
        val p = prepare(); incomplete = true
        assertTrue(service.confirm(p.token).isFailure); assertEquals(0, posts)
    }
    @Test fun noMatchingProgramCannotBeConfirmed() = runBlocking {
        val c = service.candidates("テスト番組").getOrThrow().single(); matches = false
        assertTrue(service.preview(900, AutoReserveCreateOptions(c.keyword,c.week)).isFailure); assertEquals(0, posts)
    }
    @Test fun changedEndpointAndUnauthorizedReadStopWrites() = runBlocking {
        val p = prepare(); redirected = true
        assertTrue(service.confirm(p.token).isFailure)
        redirected = false
        val next = prepare(); detailStatus = 401
        assertTrue(service.confirm(next.token).isFailure); assertEquals(0, posts)
    }
    @Test fun impendingStartStopsWrite() = runBlocking {
        val p = prepare(); start = clock + 60_000
        assertTrue(service.confirm(p.token).isFailure); assertEquals(0, posts)
    }
    @Test fun serverRetryRequestDoesNotResendPost() = runBlocking {
        val p = prepare(); status = 503
        assertTrue(service.confirm(p.token).isFailure)
        assertTrue(service.confirm(p.token).isFailure); assertEquals(1, posts)
    }
    @Test fun mismatchedSavedRuleDoesNotReportSuccess() = runBlocking {
        val p = prepare(); savedWrong = true
        assertTrue(service.confirm(p.token).isFailure); assertEquals(1, posts)
    }
    @Test fun pendingReservationsAreReportedSeparatelyFromCreatedRule() = runBlocking {
        val p = prepare(); reflect = false
        val result = service.confirm(p.token).getOrThrow()
        assertTrue(result.message.contains("まだ確認できていません")); assertEquals(1, posts)
    }
    @Test fun conflictingReservationDoesNotReportReadyToRecord() = runBlocking {
        val p = prepare(); conflict = true
        assertTrue(service.confirm(p.token).getOrThrow().message.contains("競合や重複を確認"))
    }
}
