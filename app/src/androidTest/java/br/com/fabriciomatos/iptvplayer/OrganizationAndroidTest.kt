package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OrganizationAndroidTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val source="https://example.test/list.m3u"
    @Before fun reset()=resetCatalog(context)

    @Test fun mergedMovieTabsKeepEveryRowAndRecentOnlyAddsNewRows() {
        val repository=PlaylistRepository(context)
        val groups=listOf("Filmes | Drama","♦️ Drama ✔️","Documentarios","♦️ Documentário ✔️","Filmes | Ficcao","♦️ Ficcao Cientifica ✔️","Update","XXX")
        val old=groups.mapIndexed {i,g->Channel("Filme $i","https://example.test/movie/$i",g,typeHint="movie")}
        repository.appendCache(source,Download(Playlist(old),"",""))
        val overview=repository.overview(source)
        for((group,count) in listOf("Drama" to 2,"Documentários" to 2,"Ficção científica" to 2)) {
            val aliases=overview.groups[MediaKind.MOVIES].orEmpty().filter {movieCategory(it)==group}
            assertEquals(count,repository.matchingCount(BrowseQuery(source,MediaKind.MOVIES,group=group,groupAliases=aliases)))
        }
        val recent=BrowseQuery(source,MediaKind.MOVIES,group=RECENT_GROUP,groupAliases=listOf("Update"))
        assertEquals(listOf("Filme 6"),repository.channels(recent).map {it.name})
        repository.appendCache(source,Download(Playlist(listOf(old.first(),Channel("Novo filme","https://example.test/movie/new","Drama",typeHint="movie"),
            Channel("Novo adulto","https://example.test/movie/adult","+18",typeHint="movie"))),"",""))
        assertEquals(setOf("Novo filme","Filme 6"),repository.channels(recent).map {it.name}.toSet())
        assertEquals(10,repository.overview(source).total)
        repository.close()
        PlaylistRepository(context).also {assertEquals(setOf("Novo filme","Filme 6"),it.channels(recent).map {c->c.name}.toSet());it.close()}
    }
    @Test fun importProgressOnlyReaches100AfterFullCatalogIsCommitted() {
        val repository=PlaylistRepository(context)
        repository.appendCache(source,Download(Playlist(listOf(Channel("Antigo","https://example.test/movie/old","Filmes"))),"",""))
        val file=File(context.filesDir,"catalog_pending/${PlaylistRepository.digest(source)}.ready")
        file.parentFile!!.mkdirs()
        file.bufferedWriter().use {writer->writer.write("#EXTM3U\n");repeat(6001) {i->writer.write("#EXTINF:-1 group-title=\"Filmes\",Novo $i\nhttps://example.test/movie/$i\n")}}
        val progress=mutableListOf<CatalogProgress>()
        repository.importPending(source,progress={p->
            progress.add(p)
            if(p.percent==100)assertEquals(6002,repository.overview(source).total)
        })
        assertTrue(progress.any {it.stage==CatalogStage.IMPORTING && (it.percent ?: 0) in 51..98})
        assertEquals(100,progress.last().percent)
        assertEquals(progress.mapNotNull {it.percent}.sorted(),progress.mapNotNull {it.percent})
        repository.close()
        PlaylistRepository(context).also {assertEquals(6002,it.overview(source).total);assertNotNull(it.channel(source,"https://example.test/movie/old"));it.close()}
    }
    @Test fun animeLookupUsesCorrectSeriesAndPersistsPostersForOfflineReopening() = runBlocking {
        val titles=listOf("Sono Bisque Doll wa Koi wo Suru" to "kitsu.json","Tensai Ouji no Akaji Kokka Saisei Jutsu" to "kitsu-tensai.json",
            "Shin no Nakama ja Nai to Yuusha no Party wo Oidasareta node, Henkyou de Slow Life suru Koto ni Shimashita" to "kitsu-shin.json",
            "Arifureta Shokugyou de Sekai Saikyou" to "kitsu-arifureta.json")
        val repository=PlaylistRepository(context)
        repository.appendCache(source,Download(Playlist(titles.mapIndexed {i,pair->Channel("${pair.first} S01E01","https://example.test/series/$i","Funimation Now","https://example.test/wrong-photo.jpg")}),"",""))
        val images=mutableMapOf<String,String>()
        for((title,file) in titles) {
            val json=JSONObject(instrumentation.context.assets.open(file).bufferedReader().use {it.readText()})
            val resolver=ArtworkResolver(repository) {json}
            val image=resolver.anime(source,title)!!
            assertTrue(image.contains("poster_image"));assertEquals(json.getJSONArray("data").getJSONObject(0).getJSONObject("attributes").getJSONObject("posterImage").getString("large"),image)
            images[title]=image
        }
        repository.close()
        val reopened=PlaylistRepository(context)
        val offline=ArtworkResolver(reopened) {error("Offline reopening must use saved artwork")}
        val series=reopened.series(BrowseQuery(source,MediaKind.SERIES))
        assertEquals(4,series.size)
        series.forEach {assertTrue(it.anime);assertEquals(images[it.title],it.imageUrl);assertEquals(it.imageUrl,offline.anime(source,it.title))}
        assertFalse(reopened.channels(BrowseQuery(source,MediaKind.SERIES)).any {it.imageUrl.contains("wrong-photo")})
        reopened.close()
    }
    @Test fun genericSportProgramsAreRemovedAndCategorySurvivesGuideCache() {
        val now=System.currentTimeMillis();val repository=PlaylistRepository(context)
        val channel=Channel("Esporte HD","https://example.test/live/1","Esportes",tvgId="sport")
        val guide=Guide(matches=listOf(GuideMatch("sport",Programme("Libertadores e Sulamericana",now-1000,now+60000)),
            GuideMatch("sport",Programme("Aurora x Norte",now+60000,now+120000,category="Futebol")),
            GuideMatch("sport",Programme("Chelsea vs. Bournemouth - Premier League ║ Chelsea vs. Bournemouth",now-1000,now+60000))))
        repository.saveGuide(source,guide);repository.close()
        val reopened=PlaylistRepository(context)
        val matches=footballMatches(reopened.savedGuide(source),listOf(channel),now)
        assertEquals(2,matches.size)
        assertEquals(FootballDetails("Premier League","Chelsea","Bournemouth"),matches.first().details)
        assertEquals(FootballDetails("Futebol","Aurora","Norte"),matches.last().details)
        reopened.close()
    }
}
