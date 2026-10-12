package br.com.fabriciomatos.iptvplayer

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FootballMigrationTest {
    @Test fun upgradingTheGuideFromVersionFourKeepsCatalogSourceAndFavorites() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;resetCatalog(context)
        val source="https://example.test/migration.m3u";val now=System.currentTimeMillis()
        val channel=Channel("TV Esportes","https://example.test/live/1","Esportes",tvgId="sport")
        val guide=Guide(matches=listOf(GuideMatch("sport",Programme("Fluminense x Botafogo - Campeonato Brasileiro",now,now+3600000L))))
        PlaylistRepository(context).also {
            it.saveSource(source,"Minha lista");it.appendCache(source,Download(Playlist(listOf(channel)),"",""));it.saveFavorites(setOf(channel.url));it.saveGuide(source,guide);it.close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("aurora_catalog.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("ALTER TABLE guide_programmes RENAME TO guide_with_broadcast")
            db.execSQL("CREATE TABLE guide_programmes AS SELECT source_key,channel_id,start,end,title,image,is_game,show_now,category FROM guide_with_broadcast")
            db.execSQL("DROP TABLE guide_with_broadcast")
            db.version=4
        }
        PlaylistRepository(context).also {
            assertEquals(source,it.savedUrl());assertEquals(1,it.overview(source).total);assertEquals(setOf(channel.url),it.favorites())
            assertEquals(guide,it.savedGuide(source));assertEquals(0L,it.guideChecked(source));it.close()
        }
    }
}
