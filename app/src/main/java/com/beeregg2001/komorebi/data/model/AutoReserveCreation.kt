package com.beeregg2001.komorebi.data.model

data class AutoReserveCreateCandidate(
    val programId: Long, val title: String, val channel: String,
    val startAt: Long, val keyword: String, val week: Int
)
data class AutoReserveCreateOptions(val keyword: String, val week: Int, val excludeReruns: Boolean = true)
data class AutoReserveCreatePreview(
    val token: String, val candidate: AutoReserveCreateCandidate,
    val options: AutoReserveCreateOptions, val programs: List<AutoReserveCreateCandidate>
)
data class AutoReserveCreateResult(val ruleId: Int, val message: String)
data class EsScheduleSearchOption(val option: EsRuleSearchOption, val isHalfWidth: Boolean = false, val limit: Int = 500)
data class EsAddedRule(val ruleId: Int)
