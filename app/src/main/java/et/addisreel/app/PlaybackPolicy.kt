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

    fun canRefreshRejectedLink(httpCode: Int?, attempts: Int): Boolean =
        (httpCode == 401 || httpCode == 403) && attempts < 1
}
