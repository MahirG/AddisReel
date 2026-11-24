package et.addisreel.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogCachePolicyTest {
    private val payload = """[{"id":"love","title":"Love","episodes":2}]"""

    @Test fun cachedMetadataExpiresAfterOneDayAndRejectsClockRollback() {
        val start=1_750_000_000_000L
        assertTrue(CatalogCachePolicy.isFresh(start+CatalogCachePolicy.MAX_AGE_MS,start,payload))
        assertFalse(CatalogCachePolicy.isFresh(start+CatalogCachePolicy.MAX_AGE_MS+1,start,payload))
        assertFalse(CatalogCachePolicy.isFresh(start-1,start,payload))
        assertFalse(CatalogCachePolicy.isFresh(start,0,payload))
        assertFalse(CatalogCachePolicy.isFresh(start,start,null))
        assertFalse(CatalogCachePolicy.isFresh(start,start,""))
    }

    @Test fun cachedPayloadHasStrictMemoryBound() {
        val start=1_750_000_000_000L
        assertTrue(CatalogCachePolicy.isFresh(start,start,"x".repeat(CatalogCachePolicy.MAX_PAYLOAD_CHARS)))
        assertFalse(CatalogCachePolicy.isFresh(start,start,"x".repeat(CatalogCachePolicy.MAX_PAYLOAD_CHARS+1)))
    }

    @Test fun onlyBoundedPublishedDramaMetadataCanBeStored() {
        assertTrue(CatalogCachePolicy.validPublicSeries("the-last-promise","The Last Promise","Drama","About",12))
        assertFalse(CatalogCachePolicy.validPublicSeries("../private","Title","Drama","",1))
        assertFalse(CatalogCachePolicy.validPublicSeries("UPPER","Title","Drama","",1))
        assertFalse(CatalogCachePolicy.validPublicSeries("show","","Drama","",1))
        assertFalse(CatalogCachePolicy.validPublicSeries("show","Title","","",1))
        assertFalse(CatalogCachePolicy.validPublicSeries("show","Title","Drama","",0))
        assertFalse(CatalogCachePolicy.validPublicSeries("show","Title","Drama","",10001))
        assertFalse(CatalogCachePolicy.validPublicSeries("show","Title","Drama","x".repeat(2001),1))
    }

    @Test fun onlyServerGeneratedStrongEtagsCanBePersisted() {
        val valid="\""+ "a".repeat(64) + "\""
        assertTrue(CatalogCachePolicy.validEtag(valid))
        assertFalse(CatalogCachePolicy.validEtag("W/"+valid))
        assertFalse(CatalogCachePolicy.validEtag("invalid"))
        assertFalse(CatalogCachePolicy.validEtag(null))
    }
}
