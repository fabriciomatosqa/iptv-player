package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class CatalogIdentityUnitTest {
    private val token="aZ_-09".repeat(10)+"bY_2"
    @Test fun hostRotationKeepsIdentityButDoesNotMergeOtherStreams() {
        assertEquals(catalogContentKey("http://one.test/play/$token/ts"),catalogContentKey("https://two.test:8080/play/$token/ts"))
        listOf("/play/$token", "/play/$token/ts", "/play/$token/m3u8", "/play/$token?key=one", "/play/$token?key=two", "/play/${"b2".repeat(32)}").map {catalogContentKey("https://one.test$it")}.let {assertEquals(it.size,it.toSet().size)}
        assertNotEquals(catalogContentKey("https://one.test/video/1"),catalogContentKey("https://two.test/video/1"))
        assertNotEquals(catalogContentKey("https://one.test/play/short"),catalogContentKey("https://two.test/play/short"))
        assertEquals(catalogContentKey("https://one.test/play/${"a1".repeat(32)}"),catalogContentKey("http://two.test/play/${"a1".repeat(32)}"))
    }
}
