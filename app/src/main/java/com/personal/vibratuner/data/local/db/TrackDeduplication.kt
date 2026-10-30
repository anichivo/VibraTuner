package com.personal.vibratuner.data.local.db

/**
 * Строка таблицы tracks, нужная миграции дедупликации.
 */
data class TrackDedupeRow(
    val id: Long,
    val uri: String,
    val dateAdded: Long
)

/**
 * Чистая логика миграции 3 -> 4: какие записи являются дублями по URI.
 * Оставляется самая ранняя запись (dateAdded, затем id); порядок и id
 * остальных записей не меняются, поэтому удаляются только лишние id.
 */
object TrackDeduplication {

    fun selectDuplicateIds(rows: List<TrackDedupeRow>): List<Long> {
        val earliestByUri = LinkedHashMap<String, TrackDedupeRow>()
        for (row in rows) {
            val current = earliestByUri[row.uri]
            if (current == null || isEarlier(row, current)) {
                earliestByUri[row.uri] = row
            }
        }
        val keptIds = earliestByUri.values.mapTo(HashSet()) { it.id }
        return rows.filter { it.id !in keptIds }.map { it.id }
    }

    private fun isEarlier(candidate: TrackDedupeRow, current: TrackDedupeRow): Boolean {
        if (candidate.dateAdded != current.dateAdded) {
            return candidate.dateAdded < current.dateAdded
        }
        return candidate.id < current.id
    }
}
