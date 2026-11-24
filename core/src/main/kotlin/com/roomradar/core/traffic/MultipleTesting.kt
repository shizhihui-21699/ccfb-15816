package com.roomradar.core.traffic

/**
 * Benjamini-Hochberg false discovery rate correction.
 *
 * The walk test may compare dozens of devices and several zones. Raw p < 0.05 alone would create
 * too many false motion hits. Adjust across the family of device/zone tests before treating a
 * correlation as reliable.
 */
object MultipleTesting {

    /** Returns one adjusted q-value per input; null p-values remain null. */
    fun benjaminiHochberg(pValues: List<Double?>): List<Double?> {
        val indexed = pValues.mapIndexedNotNull { index, p ->
            p?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.let { IndexedP(index, it) }
        }
        if (indexed.isEmpty()) return pValues.map { null }

        val sorted = indexed.sortedBy { it.p }
        val m = sorted.size
        val adjustedByIndex = HashMap<Int, Double>(m)
        var cumulative = 1.0

        for (rankFromEnd in m downTo 1) {
            val rank = rankFromEnd
            val item = sorted[rank - 1]
            val rawAdjusted = item.p * m / rank
            cumulative = minOf(cumulative, rawAdjusted).coerceIn(0.0, 1.0)
            adjustedByIndex[item.index] = cumulative
        }

        return pValues.mapIndexed { index, p ->
            if (p == null) null else adjustedByIndex[index]
        }
    }

    private data class IndexedP(val index: Int, val p: Double)
}
