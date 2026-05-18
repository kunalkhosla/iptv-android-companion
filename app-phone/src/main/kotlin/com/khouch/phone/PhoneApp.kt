package com.khouch.phone

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import com.khouch.phone.di.phoneModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PhoneApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PhoneApp)
            modules(phoneModule)
        }
    }

    // Coil 3 doesn't auto-discover decoders the way 2.x did — the
    // SVG decoder has to be added explicitly. Register it on the
    // singleton ImageLoader so every AsyncImage in the app can
    // load /portraits/<id>.svg without per-call configuration.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
}
