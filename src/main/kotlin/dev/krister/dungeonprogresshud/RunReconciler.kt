package dev.krister.dungeonprogresshud

internal object RunReconciler {
    /** Logs have whole-second timestamps; measurements only reconcile within that precision. */
    fun matches(record: DungeonRunRecord, floor: String, xp: Long, seconds: Int, score: Int, timestamp: Long): Boolean =
        record.floorLabel.equals(floor, true) && record.rawCataXp == xp &&
            (record.runTimeSeconds == seconds || record.runTimeSeconds == 0 || seconds == 0) &&
            (record.score == score || record.score == 0 || score == 0) &&
            kotlin.math.abs(record.timestamp - timestamp) < 2_000
}
