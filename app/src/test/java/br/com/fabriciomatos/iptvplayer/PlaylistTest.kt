package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class PlaylistTest {
    private fun parse(text: String) = M3uParser.parse(StringReader(text.trimIndent()), "https://example.test/lists/list.m3u")

    @Test fun parsesQuotedCommasHeadersAndStreamOptions() {
        val result = parse("""
            #EXTM3U url-tvg="../guide.xml.gz"
            #EXTINF:-1 tvg-id="news" tvg-logo="/logos/a,b.png" group-title="Brasil, notícias",Canal, com vírgula
            #EXTVLCOPT:http-user-agent=ProviderPlayer
            #EXTVLCOPT:http-referrer=https://example.test/
            https://cdn.example.test/live/1.m3u8
        """)
        val channel = result.channels.single()
        assertEquals("Canal, com vírgula", channel.name)
        assertEquals("Brasil, notícias", channel.group)
        assertEquals("https://example.test/logos/a,b.png", channel.imageUrl)
        assertEquals("https://example.test/guide.xml.gz", result.epgUrl)
        assertEquals("news", channel.tvgId)
        assertEquals("ProviderPlayer", channel.userAgent)
        assertEquals("https://example.test/", channel.referrer)
    }

    @Test fun removesDuplicateUrlsAndDoesNotLeakMetadata() {
        val channels = parse("""
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://example.test/logo.png",Primeiro
            https://example.test/1
            #EXTINF:-1,Repetido
            https://example.test/1
            https://example.test/2
        """).channels
        assertEquals(2, channels.size)
        assertEquals("Primeiro", channels.first().name)
        assertEquals("", channels.last().imageUrl)
    }

    @Test fun classifiesProviderPathsAndInternationalGroups() {
        assertEquals(MediaKind.LIVE, classifyContent("Canal", "Portugal", "https://example.test/tv.m3u8"))
        assertEquals(MediaKind.LIVE, classifyContent("Cinema TV", "Filmes", "https://example.test/live/1"))
        assertEquals(MediaKind.MOVIES, classifyContent("Título", "Canais", "https://example.test/movie/1"))
        assertEquals(MediaKind.SERIES, classifyContent("Título S02E12", "Brasil", "https://example.test/1"))
        assertEquals(MediaKind.SERIES, classifyContent("Título", "Séries", "https://example.test/1"))
    }

    @Test fun recognizesSingleQuotedMetadataAndRelativeStreams() {
        val c = parse("""
            #EXTM3U
            #EXTINF:-1 group-title='Séries' tvg-name='Título' tvg-logo='/logo.png',
            ../series/12.mp4
        """).channels.single()
        assertEquals("Título", c.name)
        assertEquals("https://example.test/series/12.mp4", c.url)
        assertEquals(MediaKind.SERIES, c.kind)
    }

    @Test fun ignoresServerErrorTextAndUnsupportedProtocols() {
        assertTrue(parse("Unauthorized").channels.isEmpty())
        assertTrue(parse("""
            #EXTINF:-1,Canal inválido
            ftp://example.test/stream
        """).channels.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun refusesHlsMediaPlaylistAsChannelCatalog() {
        parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts")
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesHlsMediaSequenceEvenWithAbsoluteSegments() {
        parse("#EXTM3U\\n#EXT-X-MEDIA-SEQUENCE:0\\n#EXTINF:6,\\nhttps://example.test/segment.ts")
    }

    @Test fun allowsIptvHeaderWithSessionMetadataAndRemoteChannel() {
        val channels = parse("""
            #EXTM3U
            #EXT-X-SESSION-DATA:DATA-ID="example.test",VALUE="metadata"
            #EXTINF:-1 group-title="Brasil",Canal Aurora
            https://example.test/live/1
        """).channels
        assertEquals(1, channels.size)
    }

    @Test fun filteringHandlesAccentsMultipleTermsFavoritesAndGroups() {
        val a = Channel("Notícias São Paulo", "https://example.test/a", "Brasil")
        val b = Channel("Cinema", "https://example.test/movie/b", "Filmes")
        val catalog = Catalog.build(listOf(a, b))
        assertEquals(listOf(a), filterChannels(catalog, null, false, emptySet(), null, "sao noticias"))
        assertEquals(listOf(b), filterChannels(catalog, null, true, setOf(b.url), null, ""))
        assertTrue(filterChannels(catalog, MediaKind.MOVIES, false, emptySet(), "Brasil", "").isEmpty())
    }

    @Test fun refreshPreservesSavedObjectsAndAddsOnlyNewUrls() {
        val old = Channel("Original", "https://example.test/1")
        val removed = Channel("Removido", "https://example.test/2")
        val added = Channel("Novo", "https://example.test/3")
        val result = mergePlaylist(listOf(old, removed), listOf(old.copy(), added))
        assertSame(old, result[0])
        assertEquals(listOf(old, removed, added), result)
        assertSame(removed, result[1])
        assertSame(result, mergePlaylist(result, emptyList()))
        assertSame(result, mergePlaylist(result, listOf(old.copy(name = "Renomeado"))))
    }

    @Test fun streamsLargeCatalogAndSupportsCancellation() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(20000) { append("#EXTINF:-1 group-title=\"Brasil\",Canal $it\nhttps://example.test/live/$it\n") }
        }
        assertEquals(20000, parse(text).channels.size)
        var checks = 0
        try {
            M3uParser.parse(StringReader(text), "https://example.test/list") { if (++checks > 2) throw InterruptedException() }
            fail("Cancellation must stop parsing")
        } catch (_: InterruptedException) { assertEquals(3, checks) }
    }

    @Test fun xmltvConvertsTimezonesAndRejectsInvalidDates() {
        assertEquals(xmltvTime("20261011200000 -0300"), xmltvTime("20261011230000 +0000"))
        assertTrue(xmltvTime("20261011230000 +0000") > 0)
        assertEquals(0L, xmltvTime("not-a-date"))
        assertEquals(0L, xmltvTime("20269911230000 +0000"))
    }

    @Test fun opaqueStreamsRespectProviderCategoriesAndLiveMarkers() {
        val source = "https://example.test/list"
        val metadata = mapOf("FILMES E SÉRIES" to MediaKind.LIVE, "Ação" to MediaKind.MOVIES, "Netflix" to MediaKind.SERIES)
        val channels = mutableListOf<Channel>()
        val stats = M3uParser.scan(StringReader("""
            #EXTM3U
            #EXTINF:-1 group-title="FILMES E SÉRIES",A&E
            https://example.test/play/opaque-a/ts
            #EXTINF:-1 group-title="Ação",Filme completo
            https://example.test/play/opaque-b
            #EXTINF:-1 group-title="Netflix",Rick e Morty S01E01
            https://example.test/play/opaque-c
        """.trimIndent()), source, metadata) { channels.add(it) }
        assertEquals(3, stats.entries)
        assertEquals(listOf(MediaKind.LIVE, MediaKind.MOVIES, MediaKind.SERIES), channels.map { it.kind })
        assertEquals(MediaKind.LIVE, classifyContent("A&E", "FILMES E SÉRIES", "https://example.test/play/a/ts"))
        assertEquals(MediaKind.MOVIES, classifyContent("Filme", "Ação", "https://example.test/play/b"))
    }
}
