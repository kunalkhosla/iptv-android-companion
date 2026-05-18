package com.khouch.tv.data.profile

// Theatre Portraits — mirror of public/theatre-portraits.js on
// the server. The server stores only the portrait id (short slug);
// the actual SVG lives in public/portraits/<id>.svg and is served
// unauthenticated so Coil can fetch it without juggling cookies.
//
// IDs must stay in lockstep with the server's PORTRAIT_IDS in
// server.js and the phone client's identical
// core/.../profile/Portraits.kt. Adding a new character means
// updating all three.
object Portraits {
    val IDS: List<String> = listOf(
        "chanteuse", "magician", "cat", "strongman", "mime",
        "ringmaster", "lady", "child", "acrobat",
    )

    // djb2-ish hash → portrait. Same algorithm the web + phone
    // clients use, so the same nick always picks the same
    // character across surfaces.
    private fun hash(s: String): Int {
        var h = 5381
        for (c in s.trim().lowercase()) {
            h = ((h shl 5) + h + c.code)
        }
        return kotlin.math.abs(h)
    }

    fun pickForNick(nick: String?): String =
        IDS[hash(nick.orEmpty()) % IDS.size]
}
