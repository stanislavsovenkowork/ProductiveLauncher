package dev.stanislav.focusdeck.data.repo

import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.data.db.FocusBlockDao
import kotlinx.coroutines.flow.Flow

class FocusBlockRepository(private val dao: FocusBlockDao, private val audit: SessionAudit? = null) {
    fun observeForDay(dayEpochDay: Long): Flow<List<FocusBlock>> = dao.observeForDay(dayEpochDay)

    fun observeActive(): Flow<FocusBlock?> = dao.observeActive()

    fun observeInRange(startDay: Long, endDay: Long): Flow<List<FocusBlock>> = dao.observeInRange(startDay, endDay)

    fun observeForExport(): Flow<List<FocusBlock>> = dao.observeAllForExport()

    suspend fun exportJsonl(): String = FocusEventExporter.jsonl(dao.allForExport()) + (audit?.snapshot() ?: "")

    suspend fun start(block: FocusBlock): Long = dao.insert(block)

    suspend fun update(block: FocusBlock) = dao.update(block)

    suspend fun finish(block: FocusBlock, interrupted: Boolean, anchor: FocusClockAnchor) {
        dao.update(block.afterStop(anchor.wallMillis).copy(endEpochMillis = anchor.wallMillis, interrupted = interrupted,
            endUptimeMillis = anchor.uptimeMillis, endBootCount = anchor.bootCount, endProcessId = anchor.processId))
    }
}
