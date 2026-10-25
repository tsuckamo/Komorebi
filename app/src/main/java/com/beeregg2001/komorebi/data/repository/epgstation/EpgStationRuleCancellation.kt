package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.EpgProgramSearch
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 番組名は候補探しにだけ使用し、確認済みのルールIDだけを取り消す。 */
@Singleton
class EpgStationRuleCancellation(
    private val api: EpgStationApi,
    private val now: () -> Long
) {
    @Inject constructor(api: EpgStationApi) : this(api, System::currentTimeMillis)

    private data class Draft(
        val preview: AutoReserveCancelPreview,
        val rawRule: JsonObject,
        val origin: String,
        val reserves: List<EsReserveItem>,
        val createdAt: Long
    )

    private val mutex = Mutex()
    private var draft: Draft? = null

    suspend fun candidates(keyword: String): Result<List<AutoReserveCancelCandidate>> = checked {
        require(keyword.length <= 200) { "番組名を短く指定してください。" }
        val query = EpgProgramSearch.normalize(keyword)
        require(keyword.isBlank() || query.isNotEmpty()) { "番組名を指定してください。" }
        val channels = api.getChannels().associate { it.id to it.name }
        allRules().filter { rule ->
            val title = EpgProgramSearch.normalize(rule.searchOption.keyword.orEmpty())
            query.isEmpty() || title.contains(query) || (title.length >= 3 && query.contains(title))
        }.map { candidate(it, channels) }
    }

    suspend fun preview(ruleId: Int): Result<AutoReserveCancelPreview> = checked {
        mutex.withLock {
            draft = null
            require(ruleId > 0) { "ルールIDが不正です。" }
            val response = api.getRawRule(ruleId)
            val raw = ruleBody(response, ruleId)
            val rule = Gson().fromJson(raw, EsRule::class.java)
            val channels = api.getChannels().associate { it.id to it.name }
            val reserves = relatedReserves(ruleId)
            ensureNotRecording(ruleId, reserves)
            val preview = AutoReserveCancelPreview(
                UUID.randomUUID().toString(), candidate(rule, channels),
                reserves.map { AutoReserveCancelProgram(it.id, it.name, channels[it.channelId] ?: "放送局ID ${it.channelId}", it.startAt) }
            )
            draft = Draft(preview, raw, origin(response), reserves, now())
            preview
        }
    }

    suspend fun confirm(token: String): Result<AutoReserveCancelResult> = checked {
        mutex.withLock {
            val confirmed = draft?.takeIf { it.preview.token == token }
                ?: error("確認内容が無効です。対象を選び直してください。")
            // 通信エラーでも再送しない。一度使った確認は必ず失効させる。
            draft = null
            check(now() - confirmed.createdAt in 0..120_000) { "確認から時間が経ちました。対象を選び直してください。" }
            val id = confirmed.preview.candidate.id
            val reserves = relatedReserves(id)
            check(reserves == confirmed.reserves) { "対象の予約が変更されています。最新の内容を選び直してください。" }
            ensureNotRecording(id, reserves)
            val latest = api.getRawRule(id)
            check(origin(latest) == confirmed.origin) { "接続先が変わりました。対象を選び直してください。" }
            check(ruleBody(latest, id) == confirmed.rawRule) { "ルールの条件が変更されています。対象を選び直してください。" }
            ensureTimeIsSafe(reserves)
            check(now() - confirmed.createdAt in 0..120_000) { "確認から時間が経ちました。対象を選び直してください。" }

            try {
                val deleted = api.deleteRule(id)
                if (!deleted.isSuccessful) throw HttpException(deleted)
                // ルール削除後の関連予約更新は非同期。成功応答だけで完了にしない。
                repeat(10) { attempt ->
                    val remaining = api.getRawRule(id)
                    check(origin(remaining) == confirmed.origin) { "接続先が変わりました。" }
                    if (!remaining.isSuccessful && remaining.code() != 404) throw HttpException(remaining)
                    if (remaining.code() == 404 && relatedReserves(id).isEmpty()) {
                        return@withLock AutoReserveCancelResult(
                            "「${confirmed.preview.candidate.keyword}」の毎週・自動録画ルールと、関連予約の取り消しを確認しました。録画済みの番組は残しています。"
                        )
                    }
                    if (attempt < 9) delay(300)
                }
                error("関連予約の反映を確認できませんでした。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 削除済みかもしれないため、自動再試行や完了宣言をしない。
                throw IllegalStateException("取り消しの完了を確認できませんでした。ルールは既に削除された可能性があります。EPGStationのルールと予約一覧を確認してください。")
            }
        }
    }

    private fun origin(response: Response<*>): String = response.raw().request.url.toString().substringBeforeLast('/')

    private fun ruleBody(response: Response<JsonObject>, ruleId: Int): JsonObject {
        if (!response.isSuccessful) throw HttpException(response)
        val body = response.body() ?: error("ルールを確認できませんでした。")
        check(body.get("id")?.asInt == ruleId && body.has("searchOption") && body.has("reserveOption")) {
            "ルールの内容を確認できないため中止しました。"
        }
        return body
    }

    private suspend fun allRules(): List<EsRule> = collectPages { offset ->
        api.getRules(offset, 200).let { it.rules to it.total }
    }.also { check(it.map { r -> r.id }.distinct().size == it.size) { "ルール一覧が更新中です。再度お試しください。" } }

    private suspend fun relatedReserves(ruleId: Int): List<EsReserveItem> = collectPages { offset ->
        api.getReserves(offset = offset, limit = 200).let { it.reserves to it.total }
    }.also { check(it.map { r -> r.id }.distinct().size == it.size) { "予約一覧が更新中です。再度お試しください。" } }
        .filter { it.ruleId == ruleId }.sortedBy { it.id }

    private suspend fun ensureNotRecording(ruleId: Int, reserves: List<EsReserveItem>) {
        val recording = collectPages { offset -> api.getRecordings(offset, 200).let { it.records to it.total } }
        check(recording.none { it.ruleId == ruleId || reserves.any { reserve ->
            reserve.programId != null && reserve.programId == it.programId && reserve.channelId == it.channelId
        } }) { "対象の番組は録画中です。録画を止めないため、終了後に取り消してください。" }
        ensureTimeIsSafe(reserves)
    }

    private fun ensureTimeIsSafe(reserves: List<EsReserveItem>) {
        check(reserves.none { it.startAt <= now() + 10 * 60_000L }) {
            "録画中・開始10分以内・終了未反映の予約が含まれます。録画への影響を避けるため、終了後にお試しください。"
        }
    }

    private suspend fun <T> collectPages(fetch: suspend (Int) -> Pair<List<T>, Int>): List<T> {
        val result = mutableListOf<T>()
        var expected: Int? = null
        do {
            val (items, total) = fetch(result.size)
            check(total in 0..10_000 && (expected == null || expected == total)) { "一覧が変更されたか取得上限を超えました。再度お試しください。" }
            expected = total
            check(items.isNotEmpty() || result.size == total) { "一覧を最後まで取得できませんでした。" }
            result.addAll(items)
            check(result.size <= total) { "一覧の件数が一致しません。" }
        } while (result.size < expected!!)
        return result
    }

    private fun candidate(rule: EsRule, channels: Map<Long, String>): AutoReserveCancelCandidate {
        require(rule.id > 0) { "ルールIDを確認できませんでした。" }
        val selected = rule.searchOption.channelIds?.joinToString("、") { channels[it] ?: "放送局ID $it" }
        val days = listOf("日", "月", "火", "水", "木", "金", "土")
        val times = rule.searchOption.times?.joinToString(" / ") { time ->
            val week = days.filterIndexed { index, _ -> time.week and (1 shl index) != 0 }.joinToString("・")
            "$week ${if (time.start == null || time.range == null) "終日" else if (rule.isTimeSpecification) "時刻指定あり" else "${time.start}時から${time.range}時間"}"
        } ?: "曜日・時間の制限なし"
        return AutoReserveCancelCandidate(rule.id, rule.searchOption.keyword?.takeIf { it.isNotBlank() } ?: "キーワード指定なし", buildString {
            append("ルール #${rule.id} / ${if (rule.reserveOption.enable) "有効" else "無効"}\n")
            append("対象局: ${selected ?: "放送波による指定"}\n$times\n")
            append(if (rule.isTimeSpecification) "時刻指定予約" else "EPG自動予約")
            rule.searchOption.ignoreKeyword?.takeIf { it.isNotBlank() }?.let { append(" / 除外: $it") }
        })
    }

    private suspend fun <T> checked(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        Result.failure(IllegalStateException(when (e.code()) {
            401, 403 -> "予約を操作する権限がありません。接続設定を確認してください。"
            404 -> "対象ルールが見つかりません。候補を読み直してください。"
            else -> "EPGStationの確認に失敗しました（HTTP ${e.code()}）。変更していません。"
        }))
    } catch (e: IOException) {
        Result.failure(IllegalStateException("通信に失敗したため中止しました。接続を確認してください。"))
    } catch (e: IllegalStateException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(IllegalStateException("予約情報を確認できないため中止しました。"))
    }
}
