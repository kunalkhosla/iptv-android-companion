package com.khouch.phone.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Wires the Cast SDK to the Default Media Receiver. Discovered by
 * the framework via the OPTIONS_PROVIDER_CLASS_NAME meta-data in
 * AndroidManifest. Without this, the Cast button never finds any
 * receivers.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            // Default Media Receiver app: no custom branding, plays
            // standard MP4 / HLS / DASH out of the box. Switching to
            // the Styled Media Receiver would let us put Khouch
            // branding on the cast screen, but it requires console
            // registration + a published web receiver.
            .setReceiverApplicationId("CC1AD845")
            .build()

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}
