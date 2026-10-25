package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.reflect.Proxy

class EpgStationRecordingDeletionTest {
    private lateinit var server: MockWebServer
    private lateinit var deletion: EpgStationRecordingDeletion

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EpgStationApi::class.java)
        deletion = EpgStationRecordingDeletion(api)
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun recording(extra: String = "") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"id":73,"name":"録画削除テスト","videoFiles":[{"id":901,"type":"ts"},{"id":902,"type":"encoded"}]$extra}""")

    @Test
    fun deletesRecordingIdRatherThanVideoFileId() = runBlocking {
        server.enqueue(recording())
        server.enqueue(MockResponse().setBody("""{"code":200}"""))

        assertTrue(deletion.delete(73).isSuccess)
        val detail = server.takeRequest()
        assertEquals("GET", detail.method)
        assertEquals("/api/recorded/73?isHalfWidth=false", detail.path)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/recorded/73", delete.path)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun acceptsNoContentSuccess() = runBlocking {
        server.enqueue(recording())
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(deletion.delete(73).isSuccess)
    }

    private fun assertBlocked(extra: String, message: String) = runBlocking {
        server.enqueue(recording(extra))
        val result = deletion.delete(73)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains(message))
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun protectedRecordingIsNotDeleted() = assertBlocked(",\"isProtected\":true", "保護")

    @Test
    fun activeRecordingIsNotDeleted() = assertBlocked(",\"isRecording\":true", "録画中")

    @Test
    fun encodingRecordingIsNotDeleted() = assertBlocked(",\"isEncoding\":true", "エンコード中")

    @Test
    fun wrongDetailIdIsNotDeleted() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":901,"name":"別の録画"}"""))
        assertTrue(deletion.delete(73).isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun failedPreflightNeverSendsDelete() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(deletion.delete(73).isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun deleteFailureIsNotReportedAsSuccess() = runBlocking {
        server.enqueue(recording())
        server.enqueue(MockResponse().setResponseCode(500))
        val result = deletion.delete(73)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP 500"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun invalidIdMakesNoRequest() = runBlocking {
        assertTrue(deletion.delete(0).isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test(expected = CancellationException::class)
    fun coroutineCancellationIsNotSwallowed() {
        val api = Proxy.newProxyInstance(
            EpgStationApi::class.java.classLoader,
            arrayOf(EpgStationApi::class.java)
        ) { _, _, _ -> throw CancellationException("cancelled") } as EpgStationApi
        runBlocking { EpgStationRecordingDeletion(api).delete(73) }
    }
}
