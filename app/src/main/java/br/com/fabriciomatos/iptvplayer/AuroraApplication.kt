package br.com.fabriciomatos.iptvplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** A single bounded cache reuses the same actor image across films and series. */
class AuroraApplication : Application(), ImageLoaderFactory {
    internal val castPhotos by lazy {CastPhotoRepository(this)}
    override fun newImageLoader() = ImageLoader.Builder(this)
        .memoryCache {MemoryCache.Builder(this).maxSizePercent(0.10).build()}
        .diskCache {DiskCache.Builder().directory(cacheDir.resolve("image_cache"))
            .maxSizeBytes(IMAGE_CACHE_BYTES).build()}
        .build()

    companion object {const val IMAGE_CACHE_BYTES=64L*1024*1024}
}
