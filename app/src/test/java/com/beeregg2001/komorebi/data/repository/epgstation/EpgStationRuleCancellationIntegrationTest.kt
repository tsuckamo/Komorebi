package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class EpgStationRuleCancellationIntegrationTest {
    @Test fun cancelsOnlyAnExplicitlyProvidedDisabledTestFixture() = runBlocking {
        val url = System.getenv("KOMOREBI_TEST_CANCEL_URL")
        val id = System.getenv("KOMOREBI_TEST_CANCEL_ID")?.toIntOrNull()
        val keyword = System.getenv("KOMOREBI_TEST_CANCEL_KEYWORD")
        // 通常のテスト・自動配布では実行しない。実在の毎週予約を試験に使わない。
        assumeTrue(url != null && id != null && keyword != null)
        require(keyword!!.startsWith("KomorebiCancelTest_") && keyword.length >= 24)
        val api = Retrofit.Builder().baseUrl(url!!.trimEnd('/') + "/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(EpgStationApi::class.java)
        val fixture = api.getRule(id!!)
        assertEquals(keyword, fixture.searchOption.keyword)
        assertFalse(fixture.reserveOption.enable)
        val service = EpgStationRuleCancellation(api)
        val matches = service.candidates(keyword).getOrThrow()
        assertEquals(listOf(id), matches.map { it.id })
        val preview = service.preview(id).getOrThrow()
        assertTrue(preview.programs.isEmpty())
        service.confirm(preview.token).getOrThrow()
        assertEquals(404, api.getRawRule(id).code())
    }
}
