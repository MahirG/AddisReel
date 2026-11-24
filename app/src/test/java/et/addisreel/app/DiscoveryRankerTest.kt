package et.addisreel.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryRankerTest {
    private fun item(
        key: String, genre: String, saved: Boolean = false,
        completed: Boolean = false, history: Int = -1
    ) = DiscoveryRanker.Signal(key, genre, saved, completed, history)

    @Test fun coldStartPreservesCatalogOrderAcrossDifferentGenres() {
        val items = listOf(item("a", "Drama"), item("b", "Romance"), item("c", "Mystery"))
        assertEquals(listOf("a", "b", "c"), DiscoveryRanker.recommend(items, emptyMap()))
    }

    @Test fun favoriteGenreGetsRelevantNewStoriesFirst() {
        val items = listOf(item("unrelated", "Drama"), item("romance", "Romance"))
        assertEquals("romance", DiscoveryRanker.recommend(items, mapOf("Romance" to 2)).first())
    }

    @Test fun savedTitlesArePrioritizedWhenOtherwiseEqual() {
        val items = listOf(item("general", "Drama"), item("saved", "Romance", saved = true))
        assertEquals("saved", DiscoveryRanker.recommend(items, emptyMap()).first())
    }

    @Test fun unfinishedAndUnseenOutrankCompletedAndRecentlyViewed() {
        val items = listOf(
            item("done", "Drama", completed = true),
            item("recent", "Romance", history = 0),
            item("fresh", "Mystery")
        )
        assertEquals("fresh", DiscoveryRanker.recommend(items, emptyMap()).first())
        assertEquals("done", DiscoveryRanker.recommend(items, emptyMap()).last())
    }

    @Test fun multipleGenresAreExposedInsteadOfOneGenreDominating() {
        val items = listOf(
            item("r1", "Romance"), item("r2", "Romance"),
            item("r3", "Romance"), item("m1", "Mystery")
        )
        val result = DiscoveryRanker.recommend(items, mapOf("Romance" to 1))
        assertEquals("r1", result.first())
        assertEquals("m1", result[1])
        assertEquals(4, result.distinct().size)
    }

    @Test fun limitAndDuplicateIdsAreHandledWithoutDroppingUniqueTitles() {
        val items = listOf(item("a", "Drama"), item("a", "Romance"), item("b", "Family"))
        assertEquals(listOf("a", "b"), DiscoveryRanker.recommend(items, emptyMap()))
        assertEquals(listOf("a"), DiscoveryRanker.recommend(items, emptyMap(), 1))
        assertEquals(emptyList<String>(), DiscoveryRanker.recommend(items, emptyMap(), 0))
    }

    @Test fun scoresAreSafeForUnexpectedGenreCountsAndEmptyCatalogs() {
        assertEquals(emptyList<String>(), DiscoveryRanker.recommend(emptyList(), mapOf("Drama" to 1000000)))
        assertEquals(listOf("a"), DiscoveryRanker.recommend(listOf(item("a", "Drama")), mapOf("Drama" to Int.MAX_VALUE)))
        assertEquals(emptyList<String>(), DiscoveryRanker.recommend(listOf(item("", "Drama")), emptyMap()))
    }
}
