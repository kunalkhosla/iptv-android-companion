package com.khouch.tv

import android.app.Application
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.util.DebugLogger
import com.khouch.tv.di.appModule
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class KhouchApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@KhouchApp)
            modules(appModule)
        }
    }

    // Singleton Coil image loader, tuned for low-end TV silicon.
    // Profile showed GC freeing 24 MB of bitmaps per pass with stock
    // Coil settings — that was the main source of 80% jank during
    // rail scrolling. Caps memory cache aggressively, allows hardware
    // bitmaps (live in GPU memory, no Dalvik heap pressure → no GC),
    // and disables crossfade (eliminates the transient extra bitmap
    // during image swap).
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // SVG decoder for /portraits/<id>.svg profile portraits.
            // Coil 3 doesn't auto-discover decoders; explicit registration
            // here means every AsyncImage in the TV app can render the
            // theatre portraits without per-call configuration.
            .components { add(SvgDecoder.Factory()) }
            .crossfade(false)
            .allowHardware(true)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.12)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory((this.cacheDir.resolve("coil")).toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()
}
