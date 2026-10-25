package com.beeregg2001.komorebi.data.model

data class AutoReserveCancelCandidate(val id: Int, val keyword: String, val details: String)

data class AutoReserveCancelProgram(val id: Int, val title: String, val channel: String, val startAt: Long)

/** token はアプリ内の確認画面専用。AIには渡さない。 */
data class AutoReserveCancelPreview(
    val token: String,
    val candidate: AutoReserveCancelCandidate,
    val programs: List<AutoReserveCancelProgram>
)

data class AutoReserveCancelResult(val message: String)
