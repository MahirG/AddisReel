package et.addisreel.app

/**
 * Validates only public, licensed catalog metadata cached on this device.
 * Viewing history, passwords, tokens and playback URLs are never part of it.
 * Offline catalog titles are not an entitlement: playback still checks the server.
 */
internal object CatalogCachePolicy {
    const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    const val MAX_PAYLOAD_CHARS = 200_000
    const val MAX_SERIES = 200

    private val idPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val etagPattern = Regex("\"[a-f0-9]{64}\"")

    fun isFresh(nowMs: Long, savedAtMs: Long, payload: String?): Boolean =
        payload != null && payload.length in 2..MAX_PAYLOAD_CHARS &&
            savedAtMs > 0L && nowMs >= savedAtMs &&
            nowMs - savedAtMs <= MAX_AGE_MS

    fun validPublicSeries(
        id: String, title: String, genre: String, description: String, episodes: Int
    ): Boolean = idPattern.matches(id) &&
        title.isNotBlank() && title.length <= 150 &&
        genre.isNotBlank() && genre.length <= 80 &&
        description.length <= 2_000 &&
        episodes in 1..10_000

    fun validEtag(etag: String?): Boolean = etag != null && etagPattern.matches(etag)
}
