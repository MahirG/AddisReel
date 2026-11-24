package et.addisreel.app

/**
 * Offline-only drama ranking: no account, network requests or viewer analytics.
 *
 * Use existing local Saved, History and completed-episode state. The ranking
 * encourages discovering unfinished, unvisited stories while keeping favorite
 * genres relevant and preventing a single genre from dominating the shelf.
 */
internal object DiscoveryRanker {
    data class Signal(
        val key: String,
        val genre: String,
        val saved: Boolean = false,
        val completed: Boolean = false,
        /** 0 is most recent, -1 means never opened. */
        val historyIndex: Int = -1
    )

    fun recommend(
        signals: List<Signal>,
        genreViews: Map<String, Int>,
        limit: Int = signals.size
    ): List<String> {
        if (limit <= 0) return emptyList()
        val remaining = signals.asSequence()
            .filter { it.key.isNotBlank() }
            .distinctBy { it.key }
            .toMutableList()
        if (remaining.isEmpty()) return emptyList()

        val results = ArrayList<String>(minOf(limit, remaining.size))
        val genresPicked = HashMap<String, Int>()
        while (remaining.isNotEmpty() && results.size < limit) {
            var best = 0
            var bestScore = Int.MIN_VALUE
            for (i in remaining.indices) {
                val candidate = remaining[i]
                val affinity = (genreViews[candidate.genre] ?: 0).coerceIn(0, 6)
                val visitPenalty = if (candidate.historyIndex >= 0) {
                    (16 - candidate.historyIndex.coerceAtMost(12)).coerceAtLeast(0)
                } else 0
                val score = affinity * 7 +
                    (if (candidate.saved) 12 else 0) +
                    (if (candidate.completed) -26 else 10) +
                    (if (candidate.historyIndex < 0) 8 else -visitPenalty) -
                    (genresPicked[candidate.genre] ?: 0) * 10
                // Stable original-catalog ordering breaks ties.
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            val picked = remaining.removeAt(best)
            results.add(picked.key)
            genresPicked[picked.genre] = (genresPicked[picked.genre] ?: 0) + 1
        }
        return results
    }
}
