package com.beeregg2001.komorebi.data.sync

import com.beeregg2001.komorebi.data.model.EsRecords
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class RecordingSnapshotTest {
    @Test fun onlyCompleteListingAllowsPruning() {
        val snapshot = RecordingSnapshot()
        assertNull(snapshot.completeIds())
        snapshot.addPage(3, listOf(8, 6))
        assertNull(snapshot.completeIds())
        snapshot.addPage(3, listOf(2))
        assertEquals(setOf(8, 6, 2), snapshot.completeIds())
    }
    @Test fun confirmedEmptyServerClearsStaleCache() {
        val snapshot = RecordingSnapshot()
        snapshot.addPage(0, emptyList())
        assertEquals(emptySet<Int>(), snapshot.completeIds())
    }
    @Test fun emptyIncompletePageCannotClearCache() {
        val snapshot = RecordingSnapshot()
        snapshot.addPage(2, emptyList())
        assertNull(snapshot.completeIds())
    }
    @Test fun changingTotalOrDuplicateIdsCannotClearCache() {
        val changing = RecordingSnapshot()
        changing.addPage(3, listOf(8))
        changing.addPage(2, listOf(2))
        assertNull(changing.completeIds())
        val duplicate = RecordingSnapshot()
        duplicate.addPage(2, listOf(8))
        duplicate.addPage(2, listOf(8, 2))
        assertNull(duplicate.completeIds())
    }
    @Test fun invalidIdsCannotClearCache() {
        val snapshot = RecordingSnapshot()
        snapshot.addPage(1, listOf(0))
        assertNull(snapshot.completeIds())
    }
    @Test fun missingTotalIsNotAnEmptyServer() {
        val records = Gson().fromJson("{}", EsRecords::class.java)
        val snapshot = RecordingSnapshot()
        snapshot.addPage(records.total, records.records.map { it.id })
        assertNull(snapshot.completeIds())
    }
}
