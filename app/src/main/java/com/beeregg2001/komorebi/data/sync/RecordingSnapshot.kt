package com.beeregg2001.komorebi.data.sync

/** Only a complete, consistent server listing can prove that cached recordings are gone. */
internal class RecordingSnapshot {
    private var total: Int? = null
    private var valid = true
    private val ids = mutableSetOf<Int>()

    fun addPage(expectedTotal: Int, pageIds: List<Int>) {
        if (expectedTotal < 0 || (total != null && total != expectedTotal)) valid = false
        total = expectedTotal
        for (id in pageIds) {
            if (id <= 0 || !ids.add(id)) valid = false
        }
        if (ids.size > expectedTotal) valid = false
    }

    fun completeIds(): Set<Int>? = if (valid && total != null && ids.size == total) ids.toSet() else null
}
