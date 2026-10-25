package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import java.text.Normalizer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import javax.inject.Inject

data class EpgSearchCriteria(
    val keyword: String,
    val genre: String? = null,
    val date: String? = null,
    val liveOnly: Boolean = false,
    val channelName: String? = null
)

data class EpgSearchReport(
    val items: List<EpgSearchResultItem>,
    val usedTitleFallback: Boolean,
    val scannedPrograms: Int,
    val invalidPrograms: Int,
    val criteria: EpgSearchCriteria,
    val from: OffsetDateTime,
    val until: OffsetDateTime
) {
    // 実際に使った条件を回答にも残し、単なる「0件」と区別する。
    fun summary(): String = buildString {
        append("検索語「${criteria.keyword}」")
        criteria.date?.takeIf { it.isNotBlank() }?.let { append("、日付=$it") }
        criteria.channelName?.takeIf { it.isNotBlank() }?.let { append("、放送局=$it") }
        criteria.genre?.takeIf { it.isNotBlank() }?.let { append("、ジャンル=$it") }
        if (criteria.liveOnly) append("、生中継のみ")
        append("。取得範囲=${from}〜${until}、取得番組${scannedPrograms}件、検索結果${items.size}件。")
        if (invalidPrograms > 0) append("日時不正の${invalidPrograms}件は照合できませんでした。")
        if (usedTitleFallback) append("番組表の短縮タイトルで再照合した候補です。正式名称との同一性は未確定です。")
        else if (items.isEmpty()) append("記号・全半角をそろえ、短縮タイトルでも再照合しました。")
    }
}

/** 画面のキャッシュ状態に依存せず、取得完了後にだけ検索する。取得エラーは呼び出し元へ返す。 */
class EpgProgramSearch @Inject constructor(private val provider: EpgProvider) {
    suspend fun search(
        criteria: EpgSearchCriteria,
        now: OffsetDateTime = OffsetDateTime.now()
    ): EpgSearchReport {
        val date = parseDate(criteria.date, now.toLocalDate())
        val from = (date ?: now.toLocalDate().minusDays(1)).atTime(4, 0).atOffset(now.offset)
        val until = date?.plusDays(1)?.atTime(4, 0)?.atOffset(now.offset) ?: now.plusDays(7)
        val channels = provider.getEpgPrograms(from.toString(), until.toString(), null)
        return match(channels, criteria, now, from, until, date)
    }

    companion object {
        fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        private fun parseDate(value: String?, today: LocalDate): LocalDate? = when (value?.trim()) {
            null, "" -> null
            "今日" -> today
            "明日" -> today.plusDays(1)
            else -> try {
                LocalDate.parse(value.orEmpty().trim().replace('/', '-'))
            } catch (e: Exception) {
                throw IllegalArgumentException("検索の日付を解釈できませんでした。YYYY-MM-DD形式で指定してください。", e)
            }
        }

        private fun channelKey(value: String): String = normalize(value)
            .replace("日本テレビ", "日テレ").replace("テレビ朝日", "テレ朝").replace("テレビ東京", "テレ東")

        private fun titleCore(title: String): String {
            // [字][新]などの先頭の放送記号を除き、副題より前の番組名を取り出す。
            val displayTitle = Normalizer.normalize(title, Normalizer.Form.NFKC).trim()
            val withoutMarks = displayTitle.replace(Regex("^(?:\\s*[\\[【][^\\]】]{1,12}[\\]】])+\\s*"), "")
            return normalize(withoutMarks.split(Regex("[\\s\\[【(（「『＜<:：!！?？★☆▽▼◇◆]"), limit = 2).first())
        }

        private fun match(
            channels: List<EpgChannelWrapper>, criteria: EpgSearchCriteria,
            now: OffsetDateTime, from: OffsetDateTime, until: OffsetDateTime, date: LocalDate?
        ): EpgSearchReport {
            val orSearch = criteria.keyword.contains(',') || criteria.keyword.contains('、') || criteria.keyword.contains('，')
            val terms = criteria.keyword.split(if (orSearch) Regex("[,、，]+") else Regex("\\s+"))
                .map(::normalize).filter { it.isNotBlank() }
            val hasCondition = terms.isNotEmpty() || !criteria.genre.isNullOrBlank() || date != null ||
                !criteria.channelName.isNullOrBlank() || criteria.liveOnly
            val exact = mutableListOf<EpgSearchResultItem>()
            val abbreviated = mutableListOf<EpgSearchResultItem>()
            var invalid = 0
            var scanned = 0
            channels.forEach { wrapper ->
                wrapper.programs.forEach programLoop@ { program ->
                    scanned++
                    if (!hasCondition) return@programLoop
                    val start: OffsetDateTime
                    val end: OffsetDateTime
                    try {
                        start = OffsetDateTime.parse(program.start_time).withOffsetSameInstant(now.offset)
                        end = OffsetDateTime.parse(program.end_time).withOffsetSameInstant(now.offset)
                        require(end > start)
                    } catch (e: Exception) {
                        invalid++
                        return@programLoop
                    }
                    // 放送中を含む。指定日は従来どおり午前4時区切りの放送日。
                    if (end <= now || start >= until || end <= from) return@programLoop
                    if (date != null && start.minusHours(4).toLocalDate() != date) return@programLoop
                    if (!criteria.channelName.isNullOrBlank() &&
                        !channelKey(wrapper.channel.name).contains(channelKey(criteria.channelName))) return@programLoop
                    val genre = criteria.genre?.let(::normalize).orEmpty()
                    if (genre.isNotEmpty() && program.genres?.any {
                            normalize(it.major).contains(genre) || normalize(it.middle).contains(genre)
                        } != true && !normalize(program.title).contains(genre)) return@programLoop
                    val details = program.detail.orEmpty().entries.joinToString(" ") { "${it.key} ${it.value}" }
                    val description = "${program.title} ${program.description} ${program.extended.orEmpty()} $details"
                    if (criteria.liveOnly && listOf("[生]", "【生】", "生中継", "生放送", "LIVE")
                            .none { description.contains(it, ignoreCase = true) }) return@programLoop
                    val text = normalize("${wrapper.channel.name} $description")
                    fun matches(predicate: (String) -> Boolean) = if (orSearch) terms.any(predicate) else terms.all(predicate)
                    val item = EpgSearchResultItem(program, wrapper.channel)
                    if (terms.isEmpty() || matches { text.contains(it) }) {
                        exact.add(item)
                    } else {
                        val core = titleCore(program.title)
                        // 短い一般語の偶然の一致を避ける。日付・局・ジャンル等は緩めない。
                        if (core.length >= 4 && core !in setOf("ニュース", "バラエティ", "テレビショッピング") &&
                            matches { text.contains(it) || (it.length > core.length && it.contains(core)) }) {
                            abbreviated.add(item)
                        }
                    }
                }
            }
            val fallback = exact.isEmpty() && abbreviated.isNotEmpty()
            val results = (if (exact.isNotEmpty()) exact else abbreviated)
                .distinctBy { it.channel.id to it.program.id }
                .sortedBy { OffsetDateTime.parse(it.program.start_time).toInstant() }
            return EpgSearchReport(results, fallback, scanned, invalid, criteria, from, until)
        }
    }
}
