package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.*
import com.beeregg2001.komorebi.util.TitleNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** AI は検索語を渡すだけ。番組・条件・確認はアプリが保持する。 */
@Singleton
class EpgStationRuleCreation(private val api: EpgStationApi, private val now: () -> Long) {
    @Inject constructor(api: EpgStationApi) : this(api, System::currentTimeMillis)
    private data class Choice(val item: AutoReserveCreateCandidate, val channelId: Long)
    private data class Draft(
        val preview: AutoReserveCreatePreview, val body: EsAddRuleOption,
        val program: EsScheduleProgramItem, val matches: List<EsScheduleProgramItem>,
        val origin: String, val createdAt: Long
    )
    private val mutex = Mutex()
    private var choices = emptyMap<Long, Choice>()
    private var draft: Draft? = null

    suspend fun candidates(keyword: String): Result<List<AutoReserveCreateCandidate>> = checked {
        mutex.withLock {
            draft = null
            choices = emptyMap()
            require(keyword.length in 1..200 && EpgProgramSearch.normalize(keyword).isNotBlank()) { "番組名を指定してください。" }
            val provider = object : EpgProvider {
                override suspend fun getEpgPrograms(startTime: String?, endTime: String?, channelType: String?): List<EpgChannelWrapper> {
                    val schedules = api.getSchedules(
                        OffsetDateTime.parse(startTime).toInstant().toEpochMilli(), OffsetDateTime.parse(endTime).toInstant().toEpochMilli(),
                        broadcastFlags = EpgStationDataMapper.buildBroadcastFlags()
                    )
                    val index = EpgStationDataMapper.ChannelIndex(schedules.map { it.channel })
                    return schedules.map { EpgStationDataMapper.toEpgWrapper(it, index) }
                }
                override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String) = emptyList<EpgChannelWrapper>()
            }
            val report = EpgProgramSearch(provider).search(EpgSearchCriteria(keyword), Instant.ofEpochMilli(now()).atZone(ZoneId.of("Asia/Tokyo")).toOffsetDateTime())
            val items = report.items.filter { OffsetDateTime.parse(it.program.start_time).toInstant().toEpochMilli() > now() + 600_000 }
            check(items.size <= 200) { "候補が多すぎます。番組名をもう少し具体的にしてください。" }
            choices = items.associate { result ->
                val id = result.program.id.toLong()
                val start = OffsetDateTime.parse(result.program.start_time).toInstant().toEpochMilli()
                val title = result.program.title
                val suggested = if (EpgProgramSearch.normalize(title).contains(EpgProgramSearch.normalize(keyword))) keyword.trim()
                    else TitleNormalizer.extractSearchKeyword(title)
                id to Choice(AutoReserveCreateCandidate(id, title, result.channel.name, start, suggested, dayMask(start)),
                    EpgStationDataMapper.parseChannelId(result.channel.id) ?: error("放送局を特定できません。"))
            }
            choices.values.map { it.item }
        }
    }

    suspend fun preview(programId: Long, options: AutoReserveCreateOptions): Result<AutoReserveCreatePreview> = checked {
        mutex.withLock {
            draft = null
            val choice = choices[programId] ?: error("番組の候補を選び直してください。")
            require(options.keyword.trim().length in 2..100 && EpgProgramSearch.normalize(options.keyword).length >= 2) { "検索語を2〜100文字で指定してください。" }
            require(options.week in 1..127) { "録画する曜日を選んでください。" }
            val response = api.getScheduleForRule(programId)
            val program = response.required()
            check(program.id == programId && program.channelId == choice.channelId && program.name == choice.item.title && program.startAt == choice.item.startAt) {
                "番組表が変わりました。候補を選び直してください。"
            }
            check(program.startAt > now() + 600_000) { "開始10分以内の番組です。別の放送回を選んでください。" }
            val clean = options.copy(keyword = options.keyword.trim())
            val body = ruleBody(choice.channelId, clean)
            val origin = origin(response)
            val matches = matchingPrograms(body, origin)
            check(matches.any { it.id == programId }) { "この条件では選んだ番組が対象になりません。検索語・曜日・再放送条件を見直してください。" }
            ensureNoDuplicate(body)
            val preview = AutoReserveCreatePreview(UUID.randomUUID().toString(), choice.item, clean,
                matches.map { AutoReserveCreateCandidate(it.id, it.name, choice.item.channel, it.startAt, clean.keyword, dayMask(it.startAt)) })
            draft = Draft(preview, body, program, matches, origin, now())
            preview
        }
    }

    suspend fun confirm(token: String): Result<AutoReserveCreateResult> = checked {
        mutex.withLock {
            val current = draft?.takeIf { it.preview.token == token } ?: error("確認内容が無効です。条件を確認し直してください。")
            draft = null
            fun validTime() = check(now() - current.createdAt in 0..120_000) { "確認から時間が経ちました。条件を確認し直してください。" }
            validTime()
            val latest = api.getScheduleForRule(current.program.id)
            check(origin(latest) == current.origin) { "接続先が変わりました。候補を選び直してください。" }
            check(latest.required() == current.program && current.program.startAt > now() + 600_000) { "番組表または開始時刻が変わりました。確認し直してください。" }
            check(matchingPrograms(current.body, current.origin) == current.matches) { "対象番組が変わりました。最新の条件を確認し直してください。" }
            ensureNoDuplicate(current.body)
            validTime()
            // POST の再送はしない。通信断では登録済みの可能性を明示する。
            try {
                val response = api.createConfirmedRule(current.body)
                val id = response.required().ruleId
                check(origin(response) == current.origin && id > 0) { "登録結果を確認できません。" }
                val saved = api.getRawRule(id)
                check(origin(saved) == current.origin) { "接続先が変わりました。" }
                val rule = com.google.gson.Gson().fromJson(saved.required(), EsRule::class.java)
                check(rule.id == id && !rule.isTimeSpecification && rule.reserveOption.enable &&
                    rule.searchOption.keyword == current.body.searchOption.keyword &&
                    rule.searchOption.channelIds == current.body.searchOption.channelIds &&
                    rule.searchOption.times == current.body.searchOption.times &&
                    rule.searchOption.name == true && rule.searchOption.description != true && rule.searchOption.extended != true &&
                    rule.searchOption.ignoreKeyword == current.body.searchOption.ignoreKeyword &&
                    rule.searchOption.keyRegExp != true && rule.searchOption.keyCS != true &&
                    (rule.searchOption.ignoreKeyRegExp == true) == (current.body.searchOption.ignoreKeyRegExp == true) &&
                    (rule.searchOption.ignoreName == true) == (current.body.searchOption.ignoreName == true) &&
                    rule.searchOption.ignoreDescription != true && rule.searchOption.ignoreExtended != true &&
                    !rule.reserveOption.avoidDuplicate) {
                    "保存された条件が一致しません。"
                }
                repeat(10) { attempt ->
                    val reserves = allReserves().filter { it.ruleId == id }
                    if (current.matches.all { p -> reserves.any { it.programId == p.id && it.channelId == p.channelId && !it.isSkip && !it.isConflict && !it.isOverlap && it.startAt == p.startAt } }) {
                        return@withLock AutoReserveCreateResult(id, "「${current.preview.options.keyword}」の自動録画ルール #$id と、対象${current.matches.size}件の予約反映を確認しました。今後も番組表から条件に合う放送を予約します。")
                    }
                    if (attempt < 9) delay(300)
                }
                AutoReserveCreateResult(id, "自動録画ルール #$id の登録を確認しました。予約への反映はまだ確認できていません。録画予約一覧で競合や重複を確認してください。追加登録は不要です。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                throw IllegalStateException("登録結果の照合を完了できませんでした。既に登録されている可能性があるため再送していません。EPGStationのルール・予約一覧を確認してください。", e)
            }
        }
    }

    private suspend fun matchingPrograms(body: EsAddRuleOption, expectedOrigin: String): List<EsScheduleProgramItem> {
        val response = api.previewRulePrograms(EsScheduleSearchOption(body.searchOption))
        check(origin(response) == expectedOrigin) { "接続先が変わりました。" }
        val items = response.required()
        check(items.size < 500 && items.map { it.id }.distinct().size == items.size) { "対象が多すぎるか番組表が更新中です。条件を絞ってください。" }
        val upcoming = items.filter { it.endAt > now() }.sortedBy { it.id }
        check(upcoming.isNotEmpty() && upcoming.all { it.startAt > now() + 600_000 && it.endAt > it.startAt && it.channelId in body.searchOption.channelIds.orEmpty() }) {
            "録画中・開始10分以内の番組が含まれるか、対象を確認できません。時間をおいて条件を見直してください。"
        }
        return upcoming
    }

    private suspend fun ensureNoDuplicate(body: EsAddRuleOption) {
        val rules = pages { offset -> api.getRules(offset, 200).let { it.rules to it.total } }
        check(rules.map { it.id }.distinct().size == rules.size) { "ルール一覧が更新中です。確認し直してください。" }
        val same = rules.firstOrNull {
            !it.isTimeSpecification && EpgProgramSearch.normalize(it.searchOption.keyword.orEmpty()) == EpgProgramSearch.normalize(body.searchOption.keyword.orEmpty()) &&
                (it.searchOption.channelIds.isNullOrEmpty() || it.searchOption.channelIds.any { id -> id in body.searchOption.channelIds.orEmpty() }) &&
                (it.searchOption.times.isNullOrEmpty() || it.searchOption.times.any { t -> t.week and body.searchOption.times!!.single().week != 0 })
        }
        check(same == null) { "同じ番組・局・曜日に重なるルール #${same?.id} が既にあります。新規登録せず、既存ルールを確認してください。" }
    }

    private suspend fun allReserves() = pages { offset -> api.getReserves(offset, 200).let { it.reserves to it.total } }
    private suspend fun <T> pages(fetch: suspend (Int) -> Pair<List<T>, Int>): List<T> {
        val items = mutableListOf<T>()
        var expected: Int? = null
        do {
            val (page, total) = fetch(items.size)
            check(total in 0..10_000 && (expected == null || total == expected) && (page.isNotEmpty() || items.size == total)) { "一覧を完全に取得できませんでした。" }
            expected = total
            items.addAll(page)
            check(items.size <= total) { "一覧が更新中です。" }
        } while (items.size < expected!!)
        return items
    }

    private fun ruleBody(channelId: Long, options: AutoReserveCreateOptions) = EsAddRuleOption(
        searchOption = EsRuleSearchOption(keyword = options.keyword, keyCS = false, keyRegExp = false,
            name = true, description = false, extended = false, channelIds = listOf(channelId),
            times = listOf(EsRuleTime(week = options.week)),
            ignoreKeyword = if (options.excludeReruns) "\\[再\\]|【再】|（再）|\\(再\\)|再放送|再放映" else null,
            ignoreKeyCS = if (options.excludeReruns) false else null,
            ignoreKeyRegExp = if (options.excludeReruns) true else null,
            ignoreName = options.excludeReruns, ignoreDescription = false, ignoreExtended = false),
        reserveOption = EsRuleReserveOption(enable = true, avoidDuplicate = false)
    )
    private fun dayMask(time: Long) = 1 shl (Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Tokyo")).dayOfWeek.value % 7)
    private fun origin(response: Response<*>) = response.raw().request.url.toString().substringBefore("/api/")
    private fun <T> Response<T>.required(): T {
        if (!isSuccessful) throw HttpException(this)
        return body() ?: error("サーバーからの応答を確認できません。")
    }
    private suspend fun <T> checked(block: suspend () -> T): Result<T> = try { Result.success(block()) }
    catch (e: CancellationException) { throw e }
    catch (e: HttpException) { Result.failure(IllegalStateException(if (e.code() in listOf(401, 403)) "接続先への操作権限を確認してください。登録していません。" else "番組・予約の取得に失敗しました（HTTP ${e.code()}）。登録していません。")) }
    catch (e: IllegalArgumentException) { Result.failure(e) }
    catch (e: IllegalStateException) { Result.failure(e) }
    catch (e: Exception) { Result.failure(IllegalStateException("番組・予約情報を確認できないため中止しました。登録していません。", e)) }
}
