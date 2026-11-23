package et.addisreel.app

/**
 * Small, pure playback decisions shared by Compose and the media preloader.
 * Keeping these decisions Android-independent makes regression testing fast.
 */
internal object PlaybackPolicy {
    private const val MEDIA_ORIGIN = "https://addisreel-catalog.eddieaman475.workers.dev/v1/media/"

    fun acceptsPlaybackUrl(url: String): Boolean {
        if (!url.startsWith(MEDIA_ORIGIN)) return false
        val resource = url.substringBefore('?')
        return resource.endsWith(".mp4", ignoreCase = true) || resource.endsWith(".m3u8", ignoreCase = true)
    }

    fun canPrimeMp4(url: String): Boolean =
        acceptsPlaybackUrl(url) && url.substringBefore('?').endsWith(".mp4", ignoreCase = true)

    fun freshPlaybackLink(nowMs: Long, fetchedAtMs: Long, maxAgeMs: Long): Boolean =
        fetchedAtMs >= 0L && nowMs >= fetchedAtMs &&
            nowMs - fetchedAtMs <= maxAgeMs

    fun completedPercent(watchedCount: Int, episodeCount: Int): Int =
        if (episodeCount <= 0) 0
        else ((watchedCount.coerceIn(0, episodeCount).toLong() * 100L) / episodeCount).toInt()

    /** A series is complete only when every actual episode is marked watched. */
    fun isFullyWatched(watchedEpisodes: Set<String>, episodeCount: Int): Boolean =
        episodeCount > 0 && (1..episodeCount).all { it.toString() in watchedEpisodes }

    /** Combine finished episodes with a saved in-episode resume point without double counting. */
    fun seriesProgress(
        watchedEpisodes: Set<String>,
        episodeCount: Int,
        resumeEpisode: Int,
        positionMs: Long,
        durationMs: Long
    ): Float {
        if (episodeCount <= 0) return 0f
        val watchedCount = (1..episodeCount).count { it.toString() in watchedEpisodes }
        val canCountResume = resumeEpisode in 1..episodeCount &&
            resumeEpisode.toString() !in watchedEpisodes && positionMs > 0L && durationMs > 0L
        val partial = if (canCountResume) (positionMs.toDouble() / durationMs).coerceIn(0.0, 0.99) else 0.0
        return ((watchedCount + partial) / episodeCount).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Continue from the saved episode if unfinished; otherwise skip finished episodes.
     * When a series is fully completed, Watch Again starts from episode 1.
     */
    fun resumeEpisode(savedEpisode: Int, episodeCount: Int, watchedEpisodes: Set<String>): Int {
        if (episodeCount <= 0) return 1
        val saved = savedEpisode.coerceIn(1, episodeCount)
        if (saved.toString() !in watchedEpisodes) return saved
        for (offset in 1 until episodeCount) {
            val next = ((saved - 1 + offset) % episodeCount) + 1
            if (next.toString() !in watchedEpisodes) return next
        }
        return 1
    }

    fun canRefreshRejectedLink(httpCode: Int?, attempts: Int): Boolean =
        (httpCode == 401 || httpCode == 403) && attempts < 1
}
