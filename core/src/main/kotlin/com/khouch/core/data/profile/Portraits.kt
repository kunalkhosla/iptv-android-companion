package com.khouch.core.data.profile

import com.khouch.core.data.model.Profile

// Theatre Portraits — mirror of public/theatre-portraits.js on
// the server. The server stores only the portrait id (a short
// stable slug); the actual SVG lives in public/portraits/<id>.svg
// and is served unauthenticated so Coil can fetch it.
//
// IDs match the server's PORTRAIT_IDS constant in server.js —
// keep both lists in lockstep when adding a new character.
object Portraits {
    val IDS: List<String> = listOf(
        "chanteuse", "magician", "cat", "strongman", "mime",
        "ringmaster", "lady", "child", "acrobat",
    )

    // djb2-ish hash → portrait. Same algorithm the web client
    // uses, so the same nick always picks the same character
    // regardless of which surface (web or phone) the user
    // sees first.
    private fun hash(s: String): Int {
        var h = 5381
        for (c in s.trim().lowercase()) {
            h = ((h shl 5) + h + c.code)
        }
        return kotlin.math.abs(h)
    }

    fun pickForNick(nick: String?): String =
        IDS[hash(nick.orEmpty()) % IDS.size]

    fun resolve(profile: Profile?): String {
        val chosen = profile?.avatar?.takeIf { it in IDS }
        return chosen ?: pickForNick(profile?.nick)
    }

    // Build the public CDN-style URL for a profile's portrait.
    // `serverUrl` is the active panel root (e.g. https://
    // khouch.example.com). The path is unauthenticated server-
    // side — see PUBLIC_PATHS in server.js.
    fun urlFor(serverUrl: String, profile: Profile?): String {
        val base = serverUrl.trimEnd('/')
        return "$base/portraits/${resolve(profile)}.svg"
    }
}
