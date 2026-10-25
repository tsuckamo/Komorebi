package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.*
import com.beeregg2001.komorebi.data.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** 明示した未来の一番組だけで登録・取り消しを検証し、必ず後片付けする。通常配布時は実行しない。 */
class EpgStationRuleCreationIntegrationTest {
    @Test fun createsVerifiesAndCancelsOneExplicitFutureTestRule() = runBlocking {
        assumeTrue(System.getenv("KOMOREBI_TEST_CREATE_ENABLE") == "temporary-rule")
        val url = requireNotNull(System.getenv("KOMOREBI_TEST_CREATE_URL"))
        val id = requireNotNull(System.getenv("KOMOREBI_TEST_CREATE_PROGRAM_ID")).toLong()
        val keyword = requireNotNull(System.getenv("KOMOREBI_TEST_CREATE_KEYWORD"))
        val api = Retrofit.Builder().baseUrl(url.trimEnd('/') + "/")
            .client(OkHttpClient.Builder().addInterceptor(EpgStationRuleWriteGuard()).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(EpgStationApi::class.java)
        val before = api.getRules()
        assertEquals(before.total, before.rules.size)
        require(before.rules.none { it.searchOption.keyword == keyword })
        val creation = EpgStationRuleCreation(api)
        val selected = creation.candidates(keyword).getOrThrow().single { it.programId == id }
        val preview = creation.preview(id, AutoReserveCreateOptions(keyword, 127, false)).getOrThrow()
        require(preview.programs.size == 1 && selected.startAt > System.currentTimeMillis() + 86_400_000)
        try {
            val result = creation.confirm(preview.token).getOrThrow()
            assertTrue(result.message.contains("予約反映を確認"))
            assertTrue(result.ruleId > 0)
            // 新規登録直後の予約再計算が落ち着いてから取り消しを確認する。
            kotlinx.coroutines.delay(2000)
            val cancellation = EpgStationRuleCancellation(api)
            val cancelPreview = cancellation.preview(result.ruleId).getOrThrow()
            assertEquals(1, cancelPreview.programs.size)
            cancellation.confirm(cancelPreview.token).getOrThrow()
            assertEquals(404, api.getRawRule(result.ruleId).code())
        } finally {
            kotlinx.coroutines.delay(2000)
            val after = api.getRules()
            check(after.total == after.rules.size)
            // 元々あるルールには触れず、この試験の検索語と局に一致する新規IDだけを対象にする。
            val owned = after.rules.filter { rule -> before.rules.none { it.id == rule.id } && rule.searchOption.keyword == keyword && rule.searchOption.channelIds?.size == 1 }
            check(owned.size <= 1)
            for (rule in owned) {
                val cancellation = EpgStationRuleCancellation(api)
                cancellation.confirm(cancellation.preview(rule.id).getOrThrow().token).getOrThrow()
            }
        }
    }
}
