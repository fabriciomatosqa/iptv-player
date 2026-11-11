package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuideChannelSelectionTest {
    @Test fun absentEpgSelectsOnlyVerifiedAlternateBroadcastersAndKeepsCatalogAndFavorites() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val source="local-m3u://00000000-0000-0000-0000-000000009901"
        fun channel(id:String,name:String,tvgId:String="",group:String="Esportes")=
            Channel(name,"https://example.test/live/$id",group,tvgId=tvgId,typeHint="live")
        val selected=listOf(channel("hd","ESPN 4 HD","espn4.br"),channel("fhd","ESPN 4 FHD","espn4.br"),
            channel("band","Band Sports HD"),channel("id","Provider channel","sportv2.br"),
            channel("tnt","TNT Sports FHD"))
        val rejected=listOf(channel("foreign","ESPN 4 HD","espn4.us"),
            channel("ambiguous","ESPN Extra HD"),channel("ordinary-id","TV Notícias","news.br"),
            channel("adult","ESPN HD","espn.br","+18"))
        val ordinary=(0 until 2500).map {channel("$it","Canal $it HD")}
        PlaylistRepository(context).also {repo ->
            try {
                repo.saveSource(source,"Synthetic guide selection")
                repo.appendCache(source,Download(Playlist(ordinary+selected+rejected),"",""))
                repo.saveFavorites(setOf(ordinary.first().url))
                val before=repo.overview(source).total
                val candidates=repo.alternateGuideChannels(source)
                assertEquals(selected.map {it.url}.toSet(),candidates.map {it.url}.toSet())
                assertEquals(before,repo.overview(source).total)
                assertTrue(ordinary.first().url in repo.favorites())
                assertTrue(repo.savedGuide(source).matches.isEmpty())
            } finally {repo.close()}
        }
    }

    @Test fun cancelledGuideReadsCloseTheirCursorsWithoutReturningAPartialCatalog() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val source="local-m3u://00000000-0000-0000-0000-000000009902"
        PlaylistRepository(context).also {repo ->
            try {
                repo.saveSource(source,"Synthetic cancellation")
                repo.appendCache(source,Download(Playlist(listOf(Channel("ESPN HD",
                    "https://example.test/live/a","Esportes",tvgId="espn.br",typeHint="live"))),"",""))
                for(alternate in listOf(false,true)) {
                    var checks=0
                    try {
                        val cancel={checks++;if(checks==2)throw CancellationException("Synthetic cancellation")}
                        if(alternate)repo.alternateGuideChannels(source,cancel) else repo.liveChannels(source,cancel)
                        fail("Cancellation must propagate from the channel read")
                    } catch(_:CancellationException) {assertEquals(2,checks)}
                    assertEquals(1,repo.overview(source).total)
                }
            } finally {repo.close()}
        }
    }
}
