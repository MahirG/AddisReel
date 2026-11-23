package et.addisreel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun prebufferOnlyTrustedMp4Media() {
        assertTrue(PlaybackPolicy.canPrimeMp4("https://addisreel-catalog.eddieaman475.workers.dev/v1/media/show/1/source.mp4"))
        assertTrue(PlaybackPolicy.canPrimeMp4("https://addisreel-catalog.eddieaman475.workers.dev/v1/media/show/1/source.mp4?token=short"))
        assertFalse(PlaybackPolicy.canPrimeMp4("https://addisreel-catalog.eddieaman475.workers.dev/v1/media/show/1/master.m3u8"))
        assertFalse(PlaybackPolicy.canPrimeMp4("https://other.example/v1/media/show/1/source.mp4"))
        assertFalse(PlaybackPolicy.canPrimeMp4("http://addisreel-catalog.eddieaman475.workers.dev/v1/media/show/1/source.mp4"))
    }

    @Test fun supportsHttpsMp4AndHlsQueryLinks() {
        assertTrue(PlaybackPolicy.acceptsPlaybackUrl("https://addisreel-catalog.eddieaman475.workers.dev/v1/media/show/1/source.mp4?auth=abc"))
        assertTrue(PlaybackPolicy.acceptsPlaybackUrl("https://addisreel-catalog.eddieaman475.workers.dev/v1/media/show/2/master.m3u8?auth=abc"))
        assertFalse(PlaybackPolicy.acceptsPlaybackUrl("https://other.example/v1/media/show/2/master.m3u8"))
        assertFalse(PlaybackPolicy.acceptsPlaybackUrl("https://addisreel-catalog.eddieaman475.workers.dev/v1/media/show/2/bad.exe"))
    }

    @Test fun refreshPrefetchedLinksAtExpiryBoundary() {
        assertTrue(PlaybackPolicy.freshPlaybackLink(90_000, 30_000, 60_000))
        assertFalse(PlaybackPolicy.freshPlaybackLink(90_001, 30_000, 60_000))
        assertFalse(PlaybackPolicy.freshPlaybackLink(10_000, 12_000, 60_000))
        assertTrue(PlaybackPolicy.freshPlaybackLink(30_000, 30_000, 60_000))
    }

    @Test fun completionPercentCountsOnlyWatchedEpisodes() {
        assertEquals(0, PlaybackPolicy.completedPercent(0, 12))
        assertEquals(25, PlaybackPolicy.completedPercent(3, 12))
        assertEquals(100, PlaybackPolicy.completedPercent(12, 12))
        assertEquals(100, PlaybackPolicy.completedPercent(15, 12))
        assertEquals(0, PlaybackPolicy.completedPercent(-1, 12))
        assertEquals(0, PlaybackPolicy.completedPercent(3, 0))
    }

    @Test fun onlyOneAuthRefreshForRejectedPlaybackUrl() {
        assertTrue(PlaybackPolicy.canRefreshRejectedLink(401, 0))
        assertTrue(PlaybackPolicy.canRefreshRejectedLink(403, 0))
        assertFalse(PlaybackPolicy.canRefreshRejectedLink(403, 1))
        assertFalse(PlaybackPolicy.canRefreshRejectedLink(404, 0))
        assertFalse(PlaybackPolicy.canRefreshRejectedLink(null, 0))
    }
}
