package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.epgstation.EpgStationDataMapper
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** 明示した宅内サーバーで番組表の読み取りとLLMの回答のみ確認する。予約・再生は実行しない。 */
class EpgSearchIntegrationTest {
    @Test fun actualEpgAndQwenSearch() = runBlocking {
        val baseUrl = System.getenv("KOMOREBI_TEST_EPG_URL")
        assumeTrue(!baseUrl.isNullOrBlank())
        val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
        val provider = object : EpgProvider {
            override suspend fun getEpgPrograms(startTime: String?, endTime: String?, channelType: String?): List<EpgChannelWrapper> {
                val url = (baseUrl!!.trimEnd('/') + "/api/schedules").toHttpUrl().newBuilder()
                    .addQueryParameter("startAt", OffsetDateTime.parse(startTime).toInstant().toEpochMilli().toString())
                    .addQueryParameter("endAt", OffsetDateTime.parse(endTime).toInstant().toEpochMilli().toString())
                    .addQueryParameter("isHalfWidth", "false").addQueryParameter("needsRawExtended", "true")
                EpgStationDataMapper.buildBroadcastFlags(channelType).forEach { (key, value) -> url.addQueryParameter(key, value.toString()) }
                return client.newCall(Request.Builder().url(url.build()).build()).execute().use { response ->
                    check(response.isSuccessful) { "EPG HTTP ${response.code}" }
                    val schedules = Gson().fromJson(response.body!!.string(), Array<EsSchedule>::class.java).toList()
                    val index = EpgStationDataMapper.ChannelIndex(schedules.map { it.channel })
                    schedules.map { EpgStationDataMapper.toEpgWrapper(it, index) }
                }
            }
            override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String) = emptyList<EpgChannelWrapper>()
        }
        val now = OffsetDateTime.now()
        val search = EpgProgramSearch(provider)
        val direct = search.search(EpgSearchCriteria("世界の果てまでイッテQ!", date = now.toLocalDate().toString()), now)
        assertTrue(direct.items.any { EpgProgramSearch.normalize(it.program.title).contains("イッテq") })
        assertTrue(direct.usedTitleFallback)
        println("実データの正式名検索: ${direct.summary()}")

        val settings = AiSettings(provider = "LOCAL", baseUrl = System.getenv("KOMOREBI_TEST_AI_URL"), model = System.getenv("KOMOREBI_TEST_AI_MODEL"))
        val ai = LocalAiClient()
        val systemPrompt = File(requireNotNull(System.getenv("KOMOREBI_TEST_AI_PROMPT"))).readText()
        val userPrompt = "現在時刻: $now\n対応チャンネル: 日テレ１=ch1\n現在のユーザーの指示: 今日の番組表で、世界の果てまでイッテQ!を探して。"
        val command = ai.chat(settings, systemPrompt, userPrompt)
        println("AIの検索要求: $command")
        val tag = requireNotNull(Regex("\\[REQ_EPG_SEARCH:\\s*([^\\]]+)\\]").find(command))
        val parts = tag.groupValues[1].split('|').map { it.trim() }
        val criteria = EpgSearchCriteria(parts[0], parts.getOrNull(1), parts.getOrNull(2), parts.getOrNull(3)?.toBooleanStrictOrNull() ?: false, parts.getOrNull(4))
        val result = search.search(criteria, now)
        assertTrue(result.items.any { EpgProgramSearch.normalize(it.program.title).contains("イッテq") })
        val evidence = result.items.take(5).joinToString("\n") { "${it.program.title} (${it.channel.name}, ${it.program.start_time})" }
        val answer = ai.chat(settings, systemPrompt, "$userPrompt\nシステムの検索結果: ${result.summary()}\n$evidence\n検索結果を案内してください。操作タグは禁止です。")
        println("AIの案内: $answer")
        assertTrue(EpgProgramSearch.normalize(answer).contains("イッテq"))
        assertFalse(answer.contains("0件"))
    }
}
