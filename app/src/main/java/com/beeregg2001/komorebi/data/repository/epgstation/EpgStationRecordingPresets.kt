package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import com.beeregg2001.komorebi.data.model.*
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** Do not guess an encoder from its name: follow the server's explicitly selected default. */
class EpgStationRecordingPresets(private val api: EpgStationApi) {
    suspend fun loadDefault(): EsRecordingPreset? {
        try {
            val response = api.getDefaultRecordingPreset()
            // Older EPGStation versions do not implement recording presets.
            if (response.code() == 404) return null
            if (!response.isSuccessful) throw HttpException(response)
            val preset = response.body() ?: return null
            check(preset.id > 0 && preset.isDefault && preset.settings != null) {
                "EPGStationの既定録画プリセットを確認できません。"
            }
            val settings = preset.settings
            val modes = listOfNotNull(settings.mode1, settings.mode2, settings.mode3)
            check(modes.none { it.isBlank() }) { "既定録画プリセットのエンコード指定が不正です。" }
            if (modes.isNotEmpty()) {
                val available = api.getConfig().encode.orEmpty()
                check(modes.all { it in available }) {
                    "既定録画プリセットのエンコード設定がサーバーにありません。EPGStationで確認してください。"
                }
            }
            check(!settings.isDeleteOriginalAfterEncode || modes.isNotEmpty()) {
                "元ファイル削除の指定に対応するエンコード設定がありません。"
            }
            return preset
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("録画プリセットの取得に失敗したため予約を中止しました。設定を変えずに再試行してください。", e)
        }
    }

    suspend fun addManual(programId: Long, allowEndLack: Boolean) {
        val body = manual(EsManualReserveOption(programId, allowEndLack), loadDefault())
        val response = api.addReserve(body)
        if (!response.isSuccessful) throw HttpException(response)
    }

    fun manual(base: EsManualReserveOption, preset: EsRecordingPreset?): EsManualReserveOption {
        val p = preset?.settings ?: return base
        // allowEndLack is explicitly editable in Komorebi's manual-reservation dialog.
        return base.copy(saveOption = save(p), encodeOption = encode(p), priority = p.priority,
            conflictPolicy = p.conflictPolicy, startMarginSec = p.startMarginSec, endMarginSec = p.endMarginSec,
            tags = p.tags, finishCommandName = p.finishCommandName)
    }

    fun rule(base: EsAddRuleOption, preset: EsRecordingPreset?): EsAddRuleOption {
        val p = preset?.settings ?: return base
        return base.copy(saveOption = save(p), encodeOption = encode(p), reserveOption = base.reserveOption.copy(
            allowEndLack = p.allowEndLack, priority = p.priority, conflictPolicy = p.conflictPolicy,
            startMarginSec = p.startMarginSec, endMarginSec = p.endMarginSec, tags = p.tags,
            finishCommandName = p.finishCommandName))
    }

    fun description(preset: EsRecordingPreset?): String {
        val p = preset?.settings ?: return "既定録画プリセットなし（エンコード指定なし）"
        val modes = listOfNotNull(p.mode1, p.mode2, p.mode3).joinToString(" / ").ifEmpty { "エンコードなし" }
        return "録画プリセット: ${preset.name}\n保存先: ${p.parentDirectoryName ?: "サーバー既定"} / $modes\n元ファイルの自動削除: ${if (p.isDeleteOriginalAfterEncode) "オン" else "オフ"}"
    }

    private fun save(p: EsRecordingPresetSettings): EsRuleSaveOption? =
        if (p.parentDirectoryName == null && p.directory == null && p.recordedFormat == null) null
        else EsRuleSaveOption(p.parentDirectoryName, p.directory, p.recordedFormat)

    private fun encode(p: EsRecordingPresetSettings): EsRuleEncodeOption? =
        if (p.mode1 == null && p.mode2 == null && p.mode3 == null) null
        else EsRuleEncodeOption(
            mode1 = p.mode1, encodeParentDirectoryName1 = p.encodeParentDirectoryName1.takeIf { p.mode1 != null }, directory1 = p.directory1.takeIf { p.mode1 != null },
            mode2 = p.mode2, encodeParentDirectoryName2 = p.encodeParentDirectoryName2.takeIf { p.mode2 != null }, directory2 = p.directory2.takeIf { p.mode2 != null },
            mode3 = p.mode3, encodeParentDirectoryName3 = p.encodeParentDirectoryName3.takeIf { p.mode3 != null }, directory3 = p.directory3.takeIf { p.mode3 != null },
            isDeleteOriginalAfterEncode = p.isDeleteOriginalAfterEncode)
}
