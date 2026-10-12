package br.com.fabriciomatos.iptvplayer

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SeriesTest {
    @Test fun separatesMp4EpisodesMoviesAndLiveChannels() {
        val live = Channel("Cinema TV", "https://example.test/live/1.mp4", "Filmes")
        val movie = Channel("Filme", "https://example.test/123.mp4", "Filmes")
        val episode = Channel("Rick e Morty S01E01", "https://example.test/456.mp4", "Séries")
        val portuguese = Channel("Rick e Morty Temporada 1 Episódio 2", "https://example.test/457.mp4", "Brasil")
        val catalog = Catalog.build(listOf(live, movie, episode, portuguese))
        assertEquals(listOf(live), filterChannels(catalog, MediaKind.LIVE, false, emptySet(), null, ""))
        assertEquals(listOf(movie), filterChannels(catalog, MediaKind.MOVIES, false, emptySet(), null, ""))
        assertEquals(listOf(episode, portuguese), catalog.byKind[MediaKind.SERIES])
        assertTrue(filterChannels(catalog, MediaKind.LIVE, true, setOf(movie.url), null, "").isEmpty())
    }

    @Test fun onePosterPerSeriesWithSeasonsAndNumericEpisodeOrder() {
        val channels = listOf(
            Channel("Rick e Morty S02E01", "https://example.test/series/3", "Séries"),
            Channel("Rick e Morty S01E10 [FHD]", "https://example.test/series/2", "Séries", "https://example.test/poster.jpg"),
            Channel("[BR] Rick e Morty S01E02", "https://example.test/series/1", "Séries"),
            Channel("Outra Série 1x01", "https://example.test/series/4", "Séries"))
        val catalog = Catalog.build(channels)
        assertEquals(2, catalog.series.size)
        val rick = catalog.series.single { it.title == "Rick e Morty" }
        assertEquals(listOf(1, 2), rick.seasons)
        assertEquals(listOf(2, 10, 1), rick.episodes.map { it.number })
        assertEquals("https://example.test/poster.jpg", rick.imageUrl)
        assertEquals(rick.id, catalog.seriesByEpisodeUrl[channels[0].url])
        assertEquals(listOf(rick), filterSeries(catalog, null, "rick morty"))
    }

    @Test fun explicitMetadataGroupsEpisodesWithoutEncodedNames() {
        val parsed = M3uParser.parse(StringReader("""
            #EXTM3U
            #EXTINF:-1 media-type="series" series-id="42" series-name="Rick e Morty" series-poster="/poster.jpg" season-number="2" episode-number="3",O episódio
            https://example.test/file.mp4
        """.trimIndent()), "https://example.test/list.m3u")
        val series = Catalog.build(parsed.channels).series.single()
        assertEquals("id:42", series.id)
        assertEquals("Rick e Morty", series.title)
        assertEquals("https://example.test/poster.jpg", series.imageUrl)
        assertEquals("T02 • E03", series.episodes.single().label)
    }

    @Test fun preservesRemakeYearsAndDoesNotMergeUnrelatedUnknownEpisodes() {
        val shows = groupSeries(listOf(
            Channel("Doctor Who (1963) S01E01", "https://example.test/series/1"),
            Channel("Doctor Who (2005) S01E01", "https://example.test/series/2"),
            Channel("Episode 1", "https://example.test/series/3"),
            Channel("Episode 2", "https://example.test/series/4")))
        assertEquals(4, shows.size)
        assertTrue(shows.any { it.title == "Doctor Who (2005)" })
    }

    @Test fun refreshRunsOncePerLocalCalendarDayIncludingMidnight() {
        val zone = TimeZone.getTimeZone("America/Sao_Paulo")
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }
        fun at(value: String) = format.parse(value)!!.time
        val yesterday = at("2026-10-11 23:59")
        val today = at("2026-10-12 00:01")
        assertTrue(shouldRefreshDaily(yesterday, today, zone))
        assertFalse(shouldRefreshDaily(today, at("2026-10-12 23:59"), zone))
        assertTrue(shouldRefreshDaily(0L, today, zone))
    }
}
