package et.addisreel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBudgetPolicyTest {
    @Test fun homeAndLibraryNeverResolveVideo() {
        for (id in listOf("addis-love", "series-001", "the-last-promise")) {
            assertFalse(NetworkBudgetPolicy.shouldResolveEpisode(false, id))
            assertTrue(NetworkBudgetPolicy.shouldResolveEpisode(true, id))
        }
    }

    @Test fun previewAndMalformedSeriesNeverTriggerVideoApi() {
        assertFalse(NetworkBudgetPolicy.shouldResolveEpisode(true, ""))
        assertFalse(NetworkBudgetPolicy.shouldResolveEpisode(true, "BAD"))
        assertFalse(NetworkBudgetPolicy.shouldResolveEpisode(true, "../admin"))
        assertFalse(NetworkBudgetPolicy.shouldResolveEpisode(false, ""))
    }

    @Test fun posterWarmupUsesOnlyValidatedUnmeteredHome() {
        assertTrue(NetworkBudgetPolicy.shouldWarmPosters(true, false, true, false))
        assertFalse(NetworkBudgetPolicy.shouldWarmPosters(true, false, true, true))
        assertFalse(NetworkBudgetPolicy.shouldWarmPosters(true, false, false, false))
        assertFalse(NetworkBudgetPolicy.shouldWarmPosters(true, true, true, false))
        assertFalse(NetworkBudgetPolicy.shouldWarmPosters(false, false, true, false))
    }

    @Test fun thumbnailSizeNeverExceedsItsResolutionBudget() {
        assertEquals(320, NetworkBudgetPolicy.posterWidthForTile(112))
        assertEquals(320, NetworkBudgetPolicy.posterWidthForTile(142))
        assertEquals(320, NetworkBudgetPolicy.posterWidthForTile(0))
        assertEquals(640, NetworkBudgetPolicy.posterWidthForTile(196))
        assertEquals(960, NetworkBudgetPolicy.posterWidthForTile(360))
    }
}
