package br.com.fabriciomatos.iptvplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CastPhotoRepositoryTest {
    private class MemoryCache : CastPhotoCache {
        val entries = mutableMapOf<String, CastPhotoCacheEntry>()
        override fun get(key: String, now: Long) = entries[key]?.takeIf { it.expiresAt > now }
        override fun put(key: String, entry: CastPhotoCacheEntry, now: Long) { entries[key] = entry }
    }

    private fun person(name: String = "Jason Momoa", id: Int = 1, image: Boolean = true): String =
        """{"person":{"id":$id,"name":"$name","url":"https://www.tvmaze.com/people/$id/jason",
        "image":${if (image) """{"medium":"https://static.tvmaze.com/uploads/images/medium_portrait/1/$id.jpg"}""" else "null"}}}"""

    @Test fun exactUniquePersonUsesPortraitAndCarriesAttribution() {
        val result = selectPublicCastPhoto("Jason Momoa", JSONObject("""{"items":[${person()},${person("Jason Jones", 2)}]}"""))
        assertEquals("https://static.tvmaze.com/uploads/images/medium_portrait/1/1.jpg", result.imageUrl)
        assertEquals("https://www.tvmaze.com/people/1/jason", result.sourceUrl)
        assertEquals(CastPhoto(), selectPublicCastPhoto("Jason", JSONObject("""{"items":[${person()}]}""")))
    }

    @Test fun sameNameIsAmbiguousEvenWhenOnlyOneNamesakeHasAPhoto() {
        val json = JSONObject("""{"items":[${person()},${person(id = 2, image = false)}]}""")
        assertEquals(CastPhoto(), selectPublicCastPhoto("Jason Momoa", json))
        val untrusted = JSONObject("""{"items":[{"person":{"id":1,"name":"Jason Momoa",
            "image":{"medium":"https://example.test/character.jpg"}}}]}""")
        assertEquals(CastPhoto(), selectPublicCastPhoto("Jason Momoa", untrusted))
        assertEquals("https://static.tvmaze.com/uploads/images/medium_portrait/1/1.jpg",
            selectPublicCastPhoto("JASON MOMOA", JSONObject("""{"items":[${person()}]}""")).imageUrl)
        assertTrue(selectPublicCastPhoto("José Silva", JSONObject("""{"items":[${person("Jose Silva")}]}""")).imageUrl.isNotBlank())
        assertTrue(selectPublicCastPhoto("J. Momoa", JSONObject("""{"items":[${person()}]}""")).imageUrl.isBlank())
    }

    @Test fun providerPhotosAreImmediateAndReusedAcrossWorksWithoutNetwork() = runBlocking {
        val cache = MemoryCache()
        val resolver = CastPhotoRepository(cache, request = { error("Provider photo must be reused") })
        val supplied = CastMember("Jason Momoa", "https://provider.example/actors/jason.jpg")
        resolver.remember(listOf(supplied))
        assertEquals(supplied.imageUrl, resolver.resolve(CastMember("Jason Momoa")).imageUrl)
        assertEquals(supplied.imageUrl, resolver.resolve(supplied).imageUrl)
        assertTrue(resolver.resolve(supplied).sourceUrl.isBlank())
    }

    @Test fun conflictingProviderPhotosAreNeverReusedForAnUnqualifiedNamesake() = runBlocking {
        val resolver = CastPhotoRepository(MemoryCache(), request = { error("Known ambiguous name must not be searched") })
        val first = CastMember("Alex Smith", "https://provider.example/actors/first.jpg")
        val second = CastMember("Alex Smith", "https://provider.example/actors/second.jpg")
        resolver.remember(listOf(first, second))
        assertTrue(resolver.resolve(CastMember("Alex Smith")).imageUrl.isBlank())
        assertEquals(second.imageUrl, resolver.resolve(second).imageUrl)
        assertTrue(resolver.resolve(CastMember("Alex Smith")).imageUrl.isBlank())
    }

    @Test fun positiveAndNegativeResultsExpireAndRequestsCarryOnlyActorName() = runBlocking {
        var time = 10_000L
        val requests = mutableListOf<String>()
        val resolver = CastPhotoRepository(MemoryCache(), request = {
            requests += it
            if ("Jason+Momoa" in it) JSONObject("""{"items":[${person()}]}""") else JSONObject("""{"items":[]}""")
        }, now = { time }, pause = { time += it })
        repeat(2) { assertTrue(resolver.resolve(CastMember("Jason Momoa")).imageUrl.isNotBlank()) }
        repeat(2) { assertTrue(resolver.resolve(CastMember("Missing Actor")).imageUrl.isBlank()) }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.startsWith("https://api.tvmaze.com/search/people?q=") })
        assertTrue(requests.none { "password" in it || "get.php" in it || "source=" in it })
        time += 6L * 3_600_000 + 1
        resolver.resolve(CastMember("Missing Actor"))
        assertEquals(3, requests.size)
        resolver.resolve(CastMember("Jason Momoa"))
        assertEquals(3, requests.size)
        time += 30L * 86_400_000
        resolver.resolve(CastMember("Jason Momoa"))
        assertEquals(4, requests.size)
    }

    @Test fun networkRequestsAreSpacedAndFailureBacksOffWhileCancellationPropagates() = runBlocking {
        var time = 10_000L
        val waits = mutableListOf<Long>()
        var calls = 0
        val resolver = CastPhotoRepository(MemoryCache(), request = {
            calls++
            throw java.io.IOException("Offline")
        }, now = { time }, pause = { waits += it; time += it })
        resolver.resolve(CastMember("First Actor"))
        resolver.resolve(CastMember("First Actor"))
        resolver.resolve(CastMember("Other Actor"))
        assertEquals(2, calls)
        assertEquals(listOf(650L), waits)
        time += 15L * 60_000 + 1
        resolver.resolve(CastMember("First Actor"))
        assertEquals(3, calls)
        val canceled = CastPhotoRepository(MemoryCache(), request = { throw CancellationException("Left screen") })
        try {
            canceled.resolve(CastMember("Jason Momoa"))
            fail("Cancellation must not turn into a negative photo result")
        } catch (_: CancellationException) { }
    }

    @Test fun urlOrCredentialShapedActorNamesAreNotSentToPublicSearch() = runBlocking {
        val resolver = CastPhotoRepository(MemoryCache(), request = { error("Only an actor name may leave the device") })
        listOf("https://example.test/get.php?password=private", "username=private", "Name\nHeader", "").forEach {
            assertEquals(CastPhoto(), resolver.resolve(CastMember(it)))
        }
    }

    @Test fun brokenProviderPortraitUsesInternetAndBrokenPublicPhotoDoesNotLoop() = runBlocking {
        var calls = 0
        val resolver = CastPhotoRepository(MemoryCache(), request = {
            calls++
            JSONObject("""{"items":[${person()}]}""")
        })
        val actor = CastMember("Jason Momoa", "https://provider.example/missing.jpg")
        resolver.remember(listOf(actor))
        assertEquals(actor.imageUrl, resolver.resolve(actor).imageUrl)
        val replacement = resolver.resolve(actor, failedImageUrl = actor.imageUrl)
        assertEquals(1, calls)
        assertTrue(replacement.sourceUrl.startsWith("https://www.tvmaze.com/people/"))
        assertTrue(resolver.resolve(actor, failedImageUrl = replacement.imageUrl).imageUrl.isBlank())
        assertTrue(resolver.resolve(actor).imageUrl.isBlank())
        assertEquals(1, calls)
    }

    @Test fun metadataIndexIsBoundedPersistsAndExpiresWithoutTouchingCatalog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "cast-photo-test-${System.nanoTime()}.json")
        try {
            val cache = FileCastPhotoCache(file, maxEntries = 2)
            fun entry(number: Int, expires: Long = 1_000) = CastPhotoCacheEntry(
                CastPhoto("https://provider.example/actors/$number.jpg"), CastPhotoOrigin.PROVIDER, expires)
            cache.put("first actor", entry(1), 100)
            cache.put("second actor", entry(2), 100)
            assertNotNull(cache.get("first actor", 100))
            cache.put("third actor", entry(3), 100)
            val reopened = FileCastPhotoCache(file, maxEntries = 2)
            assertNull(reopened.get("second actor", 100))
            assertNotNull(reopened.get("first actor", 100))
            assertNotNull(reopened.get("third actor", 100))
            assertNull(reopened.get("third actor", 1_001))
            assertTrue(file.length() < 2_048)
            file.writeText("damaged disposable photo cache")
            assertNull(FileCastPhotoCache(file).get("first actor", 100))
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }

    @Test fun portraitsInOlderFilmSynopsisCachesAreReusedWithoutNetworkAndIgnoreOtherArtwork() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        resetCatalog(context)
        val repository=PlaylistRepository(context)
        try {
            repository.saveArtwork("synopsis:old-film",encodeContentDetails(ContentDetails(cast=listOf(
                CastMember("Jasón Momoa","https://provider.example/old-film/jason.jpg")))).toString())
            repository.saveArtwork("public-synopsis:old-series", """{"cast":[{
                "name":"D'Arcy Carden","image":"http://provider.example/darcy.jpg"}]}""")
            repository.saveArtwork("synopsis:escaped-old-film", """{"cast":[{
                "name":"\u0056\u0069\u006f\u006c\u0061\u0020\u0044\u0061\u0076\u0069\u0073",
                "image":"https://provider.example/viola.jpg"}]}""")
            repository.saveArtwork("sports:unrelated",encodeContentDetails(ContentDetails(cast=listOf(
                CastMember("Jason Momoa","https://provider.example/wrong-sport.jpg")))).toString())
            repository.saveArtwork("synopsis:plot-only", """{"plot":"Jason Momoa",
                "cast":[{"name":"Another Person","image":"https://provider.example/wrong-person.jpg"}]}""")
            repository.saveArtwork("synopsis:oversized", JSONObject().put("padding","x".repeat(524288))
                .put("cast",org.json.JSONArray().put(JSONObject().put("name","Jason Momoa")
                    .put("image","https://provider.example/oversized.jpg"))).toString())
            val known=repository.cachedCastPhotos(listOf("JASON MOMOA","D'Arcy Carden","Viola Davis"))
            assertEquals(3,known.size)
            val resolver=CastPhotoRepository(MemoryCache(),request={error("Existing film portrait must be reused")})
            resolver.remember(known)
            assertEquals("https://provider.example/old-film/jason.jpg",resolver.resolve(CastMember("Jason Momoa")).imageUrl)
            assertEquals("http://provider.example/darcy.jpg",resolver.resolve(CastMember("D'Arcy Carden")).imageUrl)
            assertEquals("https://provider.example/viola.jpg",resolver.resolve(CastMember("Viola Davis")).imageUrl)
            repository.saveArtwork("synopsis:namesake",encodeContentDetails(ContentDetails(cast=listOf(
                CastMember("Jason Momoa","https://provider.example/other-person.jpg")))).toString())
            resolver.remember(repository.cachedCastPhotos(listOf("Jason Momoa")))
            assertTrue(resolver.resolve(CastMember("Jason Momoa")).imageUrl.isBlank())
        } finally {repository.close()}
    }
}
