package com.beeregg2001.komorebi.data.repository

import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.*

/**
 * 1. ライブ視聴・チャンネル関連の機能を提供するインターフェース
 */
interface LiveProvider {
    suspend fun getChannels(): ChannelApiResponse

    // ★ 修正: 2画面モードなどで複数のストリームを同時に開くため、streamNumber（n=0,1...）を追加
    suspend fun getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int = 0): String
    suspend fun getChannelLogoUrl(channelId: String): String
}

/**
 * 2. 録画番組関連の機能を提供するインターフェース
 */
interface RecordProvider {
    suspend fun getRecordedPrograms(page: Int = 1): RecordedApiResponse
    suspend fun getRecordedPrograms(page: Int, limit: Int): RecordedApiResponse =
        getRecordedPrograms(page)
    suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram>
    // 未対応のバックエンドでは、サーバーやローカルのデータを変更しない。
    suspend fun deleteRecordedProgram(recordedId: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("この接続先は録画削除に対応していません。"))
    suspend fun searchRecordedPrograms(keyword: String, page: Int = 1): RecordedApiResponse

    suspend fun getRecordStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double = 0.0
    ): String

    suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>>

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun keepAlive(videoId: Int, quality: String, sessionId: String)

    suspend fun getTiledThumbnailUrl(videoId: Int): String?

    suspend fun getStreamQualities(): List<StreamQuality> = emptyList()
}

/**
 * 3. 録画予約・自動予約ルールの機能を提供するインターフェース
 */
interface ReserveProvider {
    suspend fun getReserves(): Result<List<ReserveItem>>
    suspend fun addReserve(request: ReserveRequest): Result<Unit>
    suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit>
    suspend fun deleteReservation(reservationId: Int): Result<Unit>

    suspend fun getReservationConditions(): Result<List<ReservationCondition>>
    suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit>
    suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition>

    suspend fun deleteReservationCondition(conditionId: Int): Result<Unit>

    suspend fun findAutoReserveCancellationCandidates(keyword: String): Result<List<AutoReserveCancelCandidate>> =
        Result.failure(UnsupportedOperationException("AIからの毎週録画取り消しはEPGStation接続時に利用できます。"))
    suspend fun previewAutoReserveCancellation(ruleId: Int): Result<AutoReserveCancelPreview> =
        Result.failure(UnsupportedOperationException("この接続先では毎週録画の取り消し確認に対応していません。"))
    suspend fun confirmAutoReserveCancellation(token: String): Result<AutoReserveCancelResult> =
        Result.failure(UnsupportedOperationException("接続先が変わりました。対象を選び直してください。"))
}

/**
 * 4. 番組表（EPG）関連の機能を提供するインターフェース
 */
interface EpgProvider {
    suspend fun getEpgPrograms(
        startTime: String? = null,
        endTime: String? = null,
        channelType: String? = null
    ): List<EpgChannelWrapper>

    suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper>
}
