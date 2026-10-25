package com.beeregg2001.komorebi.data.repository.epgstation

import com.beeregg2001.komorebi.data.api.EpgStationApi
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/** 録画の最新状態を確認してから、録画 ID 単位で削除する。 */
class EpgStationRecordingDeletion @Inject constructor(private val api: EpgStationApi) {
    suspend fun delete(recordedId: Int): Result<Unit> {
        return try {
            require(recordedId > 0) { "録画 ID が不正です。" }
            // 一覧のキャッシュではなく、実行直前の保護・録画・エンコード状態を確認する。
            val recorded = api.getRecordedDetail(recordedId)
            check(recorded.id == recordedId) { "録画情報を確認できないため削除を中止しました。" }
            check(!recorded.isProtected) { "保護されている録画です。EPGStationで保護を解除してからお試しください。" }
            check(!recorded.isRecording) { "録画中の番組は削除できません。録画の終了後にお試しください。" }
            check(!recorded.isEncoding) { "エンコード中の番組は削除できません。処理の終了後にお試しください。" }

            // videoFileId とは採番が異なるため、必ず recordedId を使う。
            val response = api.deleteRecorded(recordedId)
            if (!response.isSuccessful) throw HttpException(response)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val message = when (e.code()) {
                401, 403 -> "削除する権限がありません。EPGStationの接続設定を確認してください。"
                404 -> "録画が見つかりません。一覧を更新して確認してください。"
                else -> "EPGStationで録画を削除できませんでした（HTTP ${e.code()}）。"
            }
            Result.failure(IllegalStateException(message, e))
        } catch (e: IOException) {
            Result.failure(IOException("通信に失敗しました。EPGStationで録画が残っているか確認してください。", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
