package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicSynopsisLookupTest {
    @Test fun providerMetadataFieldsFallbackFromMovieDataAndRoot() {
        val nested = parseContentDetails(JSONObject(
            """{"info":{},"movie_data":{"description":"Uma aventura registrada pelo provedor.","year":"2026"}}"""
        ))
        assertEquals("Uma aventura registrada pelo provedor.", nested.plot)
        val root = parseContentDetails(JSONObject("""{"overview":"Enredo na raiz do documento.","info":{}}"""))
        assertEquals("Enredo na raiz do documento.",root.plot)
    }

    @Test fun wikipediaAcceptsExactPortugueseMovieAndCreditsSource() = runBlocking {
        val seen=mutableListOf<String>()
        val lookup=PublicSynopsisLookup {url ->
            seen.add(url)
            when {
                "/search/page?" in url -> JSONObject(
                    """{"pages":[{"title":"A Odisseia (filme de 2026)","description":"filme de aventura de 2026"}]}"""
                )
                "/page/summary/" in url -> JSONObject(
                    """{"title":"A Odisseia (filme de 2026)","type":"standard",
                    "description":"filme de aventura de 2026",
                    "extract":"Um herói enfrenta numerosos desafios ao longo da sua viagem de volta para casa após a guerra.",
                    "content_urls":{"desktop":{"page":"https://pt.wikipedia.org/wiki/A_Odisseia"}}}"""
                )
                else -> error("Unexpected URL")
            }
        }
        val details=lookup.find("A Odisseia (2026) [4K]",MediaKind.MOVIES)
        assertNotNull(details)
        assertEquals("Wikipédia",details?.metadataSource)
        assertTrue(details!!.plot.contains("numerosos desafios"))
        assertTrue(seen.all { "password=" !in it && "get.php" !in it })
    }

    @Test fun ambiguousTitlesAndWrongYearDoNotAssignPlot() = runBlocking {
        val lookup=PublicSynopsisLookup {url ->
            if("/search/page?" in url) JSONObject(
                """{"pages":[{"title":"Cinema (filme de 2019)","description":"filme de 2019"},
                {"title":"Cinema (filme de 2026)","description":"filme de 2026"}]}"""
            ) else error("Should not fetch ambiguous page")
        }
        assertNull(lookup.find("Cinema",MediaKind.MOVIES))
        assertNull(lookup.find("Cinema (2028)",MediaKind.MOVIES))
        assertFalse(isTrustedMediaMatch("A Odisseia","A Odisseia (série de 2026)",
            "série de televisão",MediaKind.MOVIES,2026))
    }

    @Test fun tvmazeFallbackRequiresExactSeriesMatchAndCitesProvider() = runBlocking {
        val lookup=PublicSynopsisLookup {url ->
            when {
                "wikipedia.org" in url -> JSONObject("""{"pages":[]}""")
                "api.tvmaze.com" in url -> JSONObject(
                    """{"items":[{"show":{"name":"See","premiered":"2019-11-01",
                    "summary":"<p>Em um mundo no qual todos perderam a visão, famílias disputam sua sobrevivência.</p>",
                    "url":"https://www.tvmaze.com/shows/123/see"}}]}"""
                )
                else -> error("Unexpected URL")
            }
        }
        val result=lookup.find("See",MediaKind.SERIES)
        assertEquals("TVmaze",result?.metadataSource)
        assertTrue(result!!.plot.contains("famílias"))
    }
}
