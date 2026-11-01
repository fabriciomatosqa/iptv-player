package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class CatalogDeltaUnitTest {
    private val channel=Channel("Eu sou S01E15","https://example.test/play/"+"a".repeat(64),"Séries",imageUrl="https://images.test/15.jpg")
    @Test fun checkingAnotherGenerationDoesNotMakeSavedContentDifferent() {
        val old=CatalogImportRow.prepare(channel,1)
        val fresh=CatalogImportRow.prepare(channel,2)
        assertFalse(fresh.differsFrom(old));assertFalse(fresh.changesDirectory(old))
    }
    @Test fun realPlaybackAddressAndHeaderChangesDoNotRequireAWholeSeriesRebuild() {
        val old=CatalogImportRow.prepare(channel,1)
        val fresh=CatalogImportRow.prepare(channel.copy(url=channel.url.replace("example.test","cdn.test"),userAgent="new player"),2)
        assertEquals(old.key,fresh.key);assertTrue(fresh.differsFrom(old));assertFalse(fresh.changesDirectory(old))
    }
    @Test fun missingPosterDoesNotEraseAnExistingImageAndStoredNamesRemainStable() {
        val old=CatalogImportRow.prepare(channel,1)
        val fresh=CatalogImportRow.prepare(channel.copy(imageUrl="",name="Renamed S01E15",group="Outro grupo"),2).preserving(old)
        assertEquals(channel.imageUrl,fresh.channel.imageUrl)
        assertEquals(channel.name,fresh.channel.name);assertEquals(channel.group,fresh.channel.group)
        assertEquals(old.search,fresh.search)
    }
    @Test fun aNewSeasonOrSeriesPosterAffectsItsDirectory() {
        val old=CatalogImportRow.prepare(channel,1)
        val season=CatalogImportRow.prepare(channel.copy(name="Eu sou S02E01"),2)
        val poster=CatalogImportRow.prepare(channel.copy(seriesImageUrl="https://images.test/series.jpg"),2)
        assertTrue(season.changesDirectory(old));assertTrue(poster.changesDirectory(old))
    }
}
