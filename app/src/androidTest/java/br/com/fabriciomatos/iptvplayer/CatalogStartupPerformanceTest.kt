package br.com.fabriciomatos.iptvplayer

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in Android benchmark. Results distinguish repository reopens from real process cold starts.
 * All data and URLs are synthetic; the input is written and parsed in bounded buffers. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CatalogStartupPerformanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun url(id: Int,changed: Boolean=false)="https://${if(changed && id==15)"cdn.test" else "example.test"}/play/aZ_"+id.toString(16).padStart(61,'0')

    @Test fun measureMatrix() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("catalogBenchmark")=="true")
        val sizes=InstrumentationRegistry.getArguments().getString("catalogSizes","10000,100000,300000,600000,1200000")!!.split(',').map(String::toInt)
        val results=JSONArray()
        val output=File(context.filesDir,"catalog-benchmark.json")
        for(size in sizes) {
            require(size>=30)
            resetCatalog(context)
            val source="local-m3u://00000000-0000-0000-0000-${size.toString().padStart(12,'0')}"
            val input=File(context.cacheDir,"benchmark.m3u")
            fun write(extra: Boolean=false,changed: Boolean=false) {
                input.bufferedWriter().use {writer ->
                    writer.write("#EXTM3U\n")
                    repeat(size+(if(extra)2 else 0)) {i ->
                        val demo=i<15 || i>=size
                        val episode=if(i<size)i+1 else i-size+16
                        val group=if(demo)"Séries" else listOf("Ao Vivo","Filmes","Séries")[i%3]
                        val title=if(demo)"Benchmark Demo S01E${episode.toString().padStart(2,'0')}" else when(i%3) {
                            0->"Canal $i HD"
                            1->"Filme $i"
                            else->"Série ${i/30} S01E${(i%30+1).toString().padStart(2,'0')}"
                        }
                        writer.write("#EXTINF:-1 group-title=\"$group\",$title\n${url(i,changed)}\n")
                    }
                }
            }
            val row=JSONObject().put("rows",size).put("environment","Android instrumented; synthetic local source")
            val times=JSONObject();val cpu=JSONObject();val memory=JSONObject()
            row.put("elapsed_ms",times).put("cpu_ms",cpu).put("sampled_pss_kib",memory)
            results.put(row)
            fun save() {output.writeText(results.toString(2))}
            fun <T> measure(name: String,block: ()->T): T {
                val started=SystemClock.elapsedRealtimeNanos();val cpuStarted=Process.getElapsedCpuTime()
                val result=block()
                times.put(name,(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0)
                cpu.put(name,Process.getElapsedCpuTime()-cpuStarted)
                memory.put(name,Debug.getPss())
                save();Log.i("AuroraStartupBenchmark","$size $name ${times.getDouble(name)} ms")
                return result
            }
            write()
            var repository=PlaylistRepository(context)
            fun stage() {
                val ready=File(context.filesDir,"catalog_pending/"+PlaylistRepository.digest(source)+".ready")
                ready.parentFile!!.mkdirs();input.copyTo(ready,overwrite=true)
            }
            try {
                repository.saveSource(source,"Benchmark $size")
                stage()
                measure("first_import") {assertEquals(size,repository.importPending(source).added)}
                val first=repository.channel(source,url(15))!!
                repository.saveFavorites(setOf(first.url));repository.saveLast(first)
                repository.savePlaybackProgress(source,first.url,PlaybackProgress(21000,120000))
                repository.close();repository=PlaylistRepository(context)
                // restore remains available as the legacy recovery path, permitting a paired comparison.
                measure("legacy_restore_reopen") {repository.restore(source)}
                repository.close();repository=PlaylistRepository(context)
                measure("cache_ready_reopen") {repository.openCatalog(source)}
                for(kind in listOf(MediaKind.LIVE,MediaKind.MOVIES,MediaKind.SERIES)) {
                    measure("first_page_${kind.name.lowercase()}") {
                        val query=BrowseQuery(source,kind)
                        if(kind==MediaKind.SERIES) {
                            val shows=repository.series(query)
                            assertTrue(shows.isNotEmpty());assertTrue(shows.all {it.episodes.isEmpty()})
                        } else assertTrue(repository.channels(query).isNotEmpty())
                    }
                }
                measure("activity_session_with_navigation") {
                    val activityStarted=SystemClock.elapsedRealtimeNanos()
                    ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                        lateinit var model: AuroraViewModel
                        scenario.onActivity {model=ViewModelProvider(it)[AuroraViewModel::class.java]}
                        compose.waitUntil(20000) {model.initialized && model.catalog.total==size}
                        times.put("activity_until_initialized",(SystemClock.elapsedRealtimeNanos()-activityStarted)/1_000_000.0)
                        for(kind in listOf(MediaKind.LIVE,MediaKind.MOVIES,MediaKind.SERIES)) {
                            val expected=if(kind==MediaKind.SERIES)repository.series(BrowseQuery(source,kind),limit=1).first().title
                                else repository.channels(BrowseQuery(source,kind),limit=1).first().name
                            val pageStarted=SystemClock.elapsedRealtimeNanos()
                            scenario.onActivity {model.browse(kind)}
                            compose.waitUntil(20000) {compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()}
                            times.put("visible_page_${kind.name.lowercase()}",(SystemClock.elapsedRealtimeNanos()-pageStarted)/1_000_000.0)
                        }
                    }
                }
                stage()
                measure("unchanged_reimport") {
                    val delta=repository.importPending(source);assertEquals(0,delta.added);assertEquals(0,delta.changed)
                }
                write(extra=true)
                stage()
                measure("two_episodes_added") {
                    val delta=repository.importPending(source);assertEquals(2,delta.added);assertEquals(0,delta.changed)
                }
                write(extra=true,changed=true)
                stage()
                measure("one_url_changed") {
                    val delta=repository.importPending(source);assertEquals(0,delta.added);assertEquals(1,delta.changed)
                }
                assertEquals(size+2,repository.overview(source).total)
                assertEquals(17,repository.seriesDetail(source,seriesIdentity(Channel("Benchmark Demo S01E01",url(0),"Séries")).id)!!.episodeCount)
                assertTrue(repository.favorites().contains(url(15,true)))
                assertEquals(21000L,repository.playbackProgress(source,url(15,true))!!.positionMs)
                assertEquals(url(15,true),repository.lastUrl(source))
                row.put("database_bytes",context.getDatabasePath("aurora_catalog.db").length())
                row.put("database_with_wal_bytes",listOf("aurora_catalog.db","aurora_catalog.db-wal","aurora_catalog.db-shm").sumOf {context.getDatabasePath(it).length()})
                row.put("network","No remote source needed; cached local-source UI exercised")
                row.put("integrity","counts, URL identity, favorites, progress and series episode count verified")
                save()
            } finally {repository.close();input.delete()}
        }
        Log.i("AuroraStartupBenchmark",results.toString())
    }
}
