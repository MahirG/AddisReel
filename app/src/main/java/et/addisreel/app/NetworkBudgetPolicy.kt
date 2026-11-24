package et.addisreel.app

/**
 * Keep movie metadata discovery separate from actual video work.
 *
 * The Home, Library, Premium and Details surfaces must not resolve an episode
 * or request the next playback link until a viewer enters Watch.
 * No playback authorization is cached by this policy.
 */
internal object NetworkBudgetPolicy {
    fun shouldResolveEpisode(watchVisible: Boolean, seriesId: String): Boolean =
        watchVisible && seriesId.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))

    /** Warm poster thumbnails only on validated, unmetered Home connections. */
    fun shouldWarmPosters(
        homeVisible: Boolean,
        searchExpanded: Boolean,
        validatedNetwork: Boolean,
        metered: Boolean
    ): Boolean = homeVisible && !searchExpanded && validatedNetwork && !metered

    /** Small poster tiles should not request the hero's 960px variant. */
    fun posterWidthForTile(widthDp: Int): Int = when {
        widthDp <= 0 -> 320
        widthDp <= 160 -> 320
        widthDp <= 320 -> 640
        else -> 960
    }
}
