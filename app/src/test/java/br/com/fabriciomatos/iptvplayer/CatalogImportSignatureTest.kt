package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test

class CatalogImportSignatureTest {
    private val original=Channel(
        name="Série Demo S01E01",url="https://cdn.example.test/series/111/42.mp4",
        group="Séries",imageUrl="https://images.example.test/show.jpg",
        tvgId="show42",userAgent="Aurora",referrer="https://example.test",
        mimeType="video/mp4",seriesId="42",seriesTitle="Série Demo",
        seasonNumber=1,episodeNumber=1)
    private fun fingerprint(channel:Channel)=CatalogImportRow.prepare(channel,1).signature()

    @Test fun fingerprintIsDeterministicIndependentOfImportGeneration() {
        val first=CatalogImportRow.prepare(original,1)
        val again=CatalogImportRow.prepare(original,999)
        assertArrayEquals(first.signature(),first.signature())
        assertArrayEquals(first.signature(),again.signature())
        assertEquals(16,first.signature().size)
    }

    @Test fun aChangedStreamOrPlaybackMetadataCannotBeSkipped() {
        val expected=fingerprint(original)
        val changed=listOf(
            original.copy(url="https://cdn2.example.test/series/111/42.mp4"),
            original.copy(userAgent="DifferentAgent"),
            original.copy(referrer="https://different.example.test"),
            original.copy(mimeType="application/x-mpegURL"),
            original.copy(tvgId="new-id"),
            original.copy(imageUrl="https://images.example.test/new.jpg"),
            original.copy(typeHint="movie",kind=MediaKind.MOVIES),
            original.copy(adult=true),
            original.copy(seriesTitle="Different Series"),
            original.copy(seriesImageUrl="https://images.example.test/new-series.jpg"),
            original.copy(episodeNumber=2))
        for(candidate in changed)
            assertFalse("Changed source metadata must require full comparison",
                expected.contentEquals(fingerprint(candidate)))
    }

    @Test fun unmodifiedSourceCanBeSkippedRegardlessOfCatalogGeneration() {
        val originalRow=CatalogImportRow.prepare(original,3)
        val nextRow=CatalogImportRow.prepare(original,4)
        assertTrue(originalRow.signature().contentEquals(nextRow.signature()))
        assertFalse(nextRow.differsFrom(originalRow))
    }
}
