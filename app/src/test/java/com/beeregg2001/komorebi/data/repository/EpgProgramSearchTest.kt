package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.OffsetDateTime

class EpgProgramSearchTest {
    private val now = OffsetDateTime.parse("2026-10-25T16:00:00+09:00")
    private val channel = EpgChannel("ch1", "ch1", 1, 1, 1, 4, "041", "GR", "日テレ１", null, false, false, true)
    private fun program(title: String = "イッテＱ【欧州スペシャル】[字]", id: String = "1") = EpgProgram(
        id, "ch1", 1, 1, 1, title, "旅の番組", null, null,
        "2026-10-25T19:58:00+09:00", "2026-10-25T20:54:00+09:00", 3360, true,
        listOf(EpgGenre("バラエティ", "その他")), null, null, null
    )
    private class FakeProvider(var channels: List<EpgChannelWrapper>) : EpgProvider {
        var calls = 0
        var start: String? = null
        var end: String? = null
        var type: String? = "not-called"
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun getEpgPrograms(startTime: String?, endTime: String?, channelType: String?): List<EpgChannelWrapper> {
            calls++
            start = startTime; end = endTime; type = channelType
            gate?.await()
            failure?.let { throw it }
            return channels
        }
        override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String) = emptyList<EpgChannelWrapper>()
    }
    private fun provider(vararg programs: EpgProgram) = FakeProvider(listOf(EpgChannelWrapper(channel, programs.toList())))
    private fun search(criteria: EpgSearchCriteria, vararg programs: EpgProgram) = runBlocking {
        EpgProgramSearch(provider(*programs)).search(criteria, now)
    }

    @Test fun punctuationWidthAndCaseAreEquivalent() {
        val result = search(EpgSearchCriteria("ｲｯﾃq！"), program())
        assertEquals(1, result.items.size)
        assertFalse(result.usedTitleFallback)
    }
    @Test fun fullNameFindsAbbreviatedEpgTitle() {
        val result = search(EpgSearchCriteria("世界の果てまでイッテQ!"), program())
        assertEquals(1, result.items.size)
        assertTrue(result.usedTitleFallback)
        assertTrue(result.summary().contains("候補"))
    }
    @Test fun leadingBroadcastMarksDoNotHideTitle() {
        assertEquals(1, search(EpgSearchCriteria("世界の果てまでイッテQ!"), program("[新][字]イッテＱ　特別編")).items.size)
    }
    @Test fun fallbackIsNotSpecificToOneProgram() {
        val result = search(EpgSearchCriteria("金曜特選探検クラブ!"), program("探検クラブ【山の旅】"))
        assertTrue(result.usedTitleFallback)
        assertEquals(1, result.items.size)
    }
    @Test fun exactResultsTakePriorityOverAbbreviatedCandidates() {
        val result = search(EpgSearchCriteria("世界の果てまでイッテQ"), program(), program("世界の果てまでイッテQ", "2"))
        assertFalse(result.usedTitleFallback)
        assertEquals(listOf("2"), result.items.map { it.program.id })
    }
    @Test fun shortAndGenericTitlesAreNotFallbackMatches() {
        assertTrue(search(EpgSearchCriteria("世界の果てまでイッテQ"), program("世界【特集】")).items.isEmpty())
        assertTrue(search(EpgSearchCriteria("深夜ニュース便"), program("ニュース【特集】")).items.isEmpty())
    }
    @Test fun andTermsAreKeptDuringFallback() {
        assertTrue(search(EpgSearchCriteria("世界の果てまでイッテQ ドラマ"), program()).items.isEmpty())
        assertEquals(1, search(EpgSearchCriteria("世界の果てまでイッテQ 旅"), program()).items.size)
    }
    @Test fun commaSeparatedTermsRemainOrSearch() {
        assertEquals(1, search(EpgSearchCriteria("存在しない番組，イッテQ!"), program()).items.size)
    }
    @Test fun channelAliasesNormalizeWithoutDroppingChannelConstraint() {
        assertEquals(1, search(EpgSearchCriteria("イッテQ", channelName = "日本テレビ"), program()).items.size)
        assertTrue(search(EpgSearchCriteria("世界の果てまでイッテQ", channelName = "テレビ東京"), program()).items.isEmpty())
    }
    @Test fun fallbackPreservesGenreDateAndLiveConstraints() {
        listOf(
            EpgSearchCriteria("世界の果てまでイッテQ", genre = "ドラマ"),
            EpgSearchCriteria("世界の果てまでイッテQ", date = "2026-10-26"),
            EpgSearchCriteria("世界の果てまでイッテQ", liveOnly = true)
        ).forEach { assertTrue(search(it, program()).items.isEmpty()) }
    }
    @Test fun liveOnlyMatchesBroadcastMarker() {
        assertEquals(1, search(EpgSearchCriteria("イッテQ", liveOnly = true), program("[生]イッテQ")).items.size)
    }
    @Test fun currentlyAiringProgramIsIncludedAndEndedProgramIsExcluded() {
        val ongoing = program().copy(start_time = "2026-10-25T15:30:00+09:00", end_time = "2026-10-25T16:30:00+09:00")
        assertEquals(1, search(EpgSearchCriteria("イッテQ"), ongoing).items.size)
        assertTrue(search(EpgSearchCriteria("イッテQ"), ongoing.copy(end_time = now.toString())).items.isEmpty())
    }
    @Test fun requestedBroadcastDayIncludesFollowingEarlyMorning() {
        val lateNight = program().copy(start_time = "2026-10-26T02:00:00+09:00", end_time = "2026-10-26T03:00:00+09:00")
        assertEquals(1, search(EpgSearchCriteria("イッテQ", date = "2026/10/25"), lateNight).items.size)
        assertTrue(search(EpgSearchCriteria("イッテQ", date = "2026-10-26"), lateNight).items.isEmpty())
    }
    @Test fun timestampsAreComparedInLocalTimezone() {
        val utc = program().copy(start_time = "2026-10-25T17:00:00Z", end_time = "2026-10-25T18:00:00Z")
        assertEquals(1, search(EpgSearchCriteria("イッテQ", date = "2026-10-25"), utc).items.size)
    }
    @Test fun missingDateIsSevenDaysAndExplicitDateHasBoundedFetch() = runBlocking {
        val fake = provider(program())
        val service = EpgProgramSearch(fake)
        service.search(EpgSearchCriteria("イッテQ", date = "今日"), now)
        assertEquals("2026-10-25T04:00+09:00", fake.start)
        assertEquals("2026-10-26T04:00+09:00", fake.end)
        assertNull(fake.type)
        service.search(EpgSearchCriteria("イッテQ"), now)
        assertEquals(now.plusDays(7), OffsetDateTime.parse(fake.end))
    }
    @Test fun malformedDateIsAnErrorNotUnrestrictedSearch() = runBlocking {
        val fake = provider(program())
        try {
            EpgProgramSearch(fake).search(EpgSearchCriteria("イッテQ", date = "2026-99-01"), now)
            fail("不正な日付は成功扱いにしない")
        } catch (expected: IllegalArgumentException) {
            assertEquals(0, fake.calls)
        }
    }
    @Test fun invalidProgramDatesAreReportedAndDoNotDiscardValidPrograms() {
        val result = search(EpgSearchCriteria("イッテQ"), program(), program(id = "2").copy(start_time = "invalid"))
        assertEquals(1, result.items.size)
        assertEquals(1, result.invalidPrograms)
        assertEquals(2, result.scannedPrograms)
    }
    @Test fun duplicateProgramsAreRemoved() {
        assertEquals(1, search(EpgSearchCriteria("イッテQ"), program(), program()).items.size)
    }
    @Test fun emptyOrPunctuationOnlyQueryDoesNotMatchEverything() {
        assertTrue(search(EpgSearchCriteria(""), program()).items.isEmpty())
        assertTrue(search(EpgSearchCriteria("!!!"), program()).items.isEmpty())
    }
    @Test fun failedFetchIsNotReportedAsZeroMatches() = runBlocking {
        val fake = provider(program()).apply { failure = IOException("test failure") }
        try {
            EpgProgramSearch(fake).search(EpgSearchCriteria("イッテQ"), now)
            fail("取得失敗を0件に変換しない")
        } catch (expected: IOException) {
            assertEquals("test failure", expected.message)
        }
    }
    @Test fun searchWaitsForDataAndRefreshesOnNextRequest() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fake = provider(program()).apply { this.gate = gate }
        val service = EpgProgramSearch(fake)
        val pending = async { service.search(EpgSearchCriteria("イッテQ"), now) }
        yield()
        assertFalse(pending.isCompleted)
        gate.complete(Unit)
        assertEquals(1, pending.await().items.size)
        fake.channels = emptyList()
        assertTrue(service.search(EpgSearchCriteria("イッテQ"), now).items.isEmpty())
        assertEquals(2, fake.calls)
    }
}
