package br.com.fabriciomatos.iptvplayer

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FootballMetadataAndroidTest {
    @Test fun xmltvMetadataSurvivesPersistenceAndARefreshWithCleanedTitles() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;resetCatalog(context)
        val now=xmltvTime("20261017230000 +0000")
        val xml="""<tv><channel id="a"><display-name>ESPN</display-name></channel>
            <programme channel="a" start="20261017230000 +0000" stop="20261018010000 +0000">
            <sub-title>Belgrano x Palmeiras</sub-title><title>Libertadores Feminino</title>
            <desc>Disputa da Copa Libertadores Feminina</desc><category>Futebol</category><live/>
            <competition>Copa Libertadores Feminina</competition><gender>female</gender><country>INT</country>
            </programme></tv>"""
        val guide=parseGuide(xml.byteInputStream(),"https://example.test/guide.xml",setOf("a"),now)
        val p=guide.matches.single().programme
        assertEquals("Libertadores Feminino",p.metadata.originalTitle)
        assertEquals("Belgrano x Palmeiras",p.metadata.subtitle)
        assertEquals("Disputa da Copa Libertadores Feminina",p.metadata.description)
        val channel=Channel("ESPN HD","https://example.test/live/a","Futebol",tvgId="a")
        PlaylistRepository(context).also { it.saveGuide("fixture",guide,now);it.close() }
        PlaylistRepository(context).also { repository ->
            assertEquals(guide,repository.savedGuide("fixture"))
            val first=footballMatches(repository.savedGuide("fixture"),listOf(channel),now).single()
            assertEquals("Belgrano",first.details.home);assertEquals("Palmeiras",first.details.away)
            assertEquals(FOOTBALL_WOMEN,footballTabKey(first.details))
            val clean=Guide(matches=listOf(GuideMatch("a",p.copy(title="Belgrano x Palmeiras",metadata=FootballMetadata()))))
            repository.saveGuide("fixture",clean,now+60000)
            val refreshed=footballMatches(repository.savedGuide("fixture"),listOf(channel),now).single()
            assertEquals(first.id,refreshed.id);assertEquals(first.channels,refreshed.channels)
            assertEquals(FOOTBALL_WOMEN,footballTabKey(refreshed.details))
            assertEquals(BroadcastHint.LIVE,refreshed.programme.broadcast)
            repository.close()
        }
    }
    @Test fun versionNineRawRecordsAreReinterpretedWithoutDeletingCatalogOrFavorites() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;resetCatalog(context)
        val source="https://example.test/old-guide.m3u";val now=System.currentTimeMillis()
        val channels=listOf("a","b","c").map { Channel("ESPN $it HD","https://example.test/live/$it","Futebol",tvgId=it) }
        val titles=listOf("Libertadores Feminino - Belgrano x Palmeiras","Libertadores Feminina - Corinthians x Caracas",
            "Belgrano x Palmeiras - Copa Libertadores Feminina")
        PlaylistRepository(context).also { repo ->
            repo.saveSource(source,"Minha lista");repo.appendCache(source,Download(Playlist(channels),"",""))
            repo.saveFavorites(setOf(channels.first().url))
            repo.saveGuide(source,Guide(matches=titles.mapIndexed { i,t -> GuideMatch(channels[i].tvgId,Programme(t,now,now+7200000)) }))
            repo.close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE guide_programmes RENAME TO guide_v10")
            db.execSQL("CREATE TABLE guide_programmes AS SELECT source_key,channel_id,start,end,title,image,is_game,show_now,category,broadcast FROM guide_v10")
            db.execSQL("UPDATE guide_programmes SET is_game=0")
            db.execSQL("DROP TABLE guide_v10");db.version=9
        }
        PlaylistRepository(context).also { repo ->
            assertEquals(source,repo.savedUrl());assertEquals(3,repo.overview(source).total)
            assertEquals(setOf(channels.first().url),repo.favorites());assertEquals(0L,repo.guideChecked(source))
            val games=footballMatches(repo.savedGuide(source),repo.liveChannels(source),now)
            assertEquals(2,games.size);assertTrue(games.all { footballTabKey(it.details)==FOOTBALL_WOMEN })
            assertEquals(3,games.sumOf { it.channels.size })
            assertEquals(listOf(FOOTBALL_LIVE,FOOTBALL_GAMES,FOOTBALL_WOMEN),footballTabs(games).map { it.key })
            repo.close()
        }
    }
}
