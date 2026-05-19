package com.khouch.tv.ui.common

import com.khouch.tv.data.model.FilterConfig
import com.khouch.tv.data.model.PosterResponse
import com.khouch.tv.data.model.Profile
import java.util.Calendar

// Server-supplied kids-cert tiers are authoritative when present. Each
// tier ADDS the listed certs at or above its minAge. Old servers (or
// pre-bootstrap state) get the hardcoded fallback below — same table
// the web client falls back to, kept byte-for-byte equivalent.
fun allowedCertsForAge(age: Int?, config: FilterConfig? = null): Set<String>? {
    if (age == null) return null
    val tiers = config?.kidsCertTiers
    if (!tiers.isNullOrEmpty()) {
        val out = HashSet<String>()
        for (t in tiers) if (age >= t.minAge) out.addAll(t.add)
        return out
    }
    val movies = mutableListOf("G")
    val tv = mutableListOf("TV-Y", "TV-G")
    if (age >= 7) { movies += "PG"; tv += "TV-Y7"; tv += "TV-PG" }
    if (age >= 10) movies += "PG-13"
    if (age >= 13) tv += "TV-14"
    return (movies + tv).toSet()
}

fun deriveKidsAge(profile: Profile?): Int? {
    val by = profile?.kidsBirthYear ?: return null
    val age = Calendar.getInstance().get(Calendar.YEAR) - by
    return age.takeIf { it in 0..17 }
}

// Returns true if the item is allowed in the active profile's kids mode.
//   - mode "live" → always true (no TMDB cert data for broadcast)
//   - kidsAge null → always true (not a kids profile)
//   - cached TMDB cert in allowed set → true
//   - otherwise → false (uncached / unrated / above threshold → hidden)
fun isKidSafe(
    kidsAge: Int?,
    mode: String,
    posterCacheGet: (Int) -> PosterResponse?,
    itemId: Int,
    config: FilterConfig? = null,
): Boolean {
    if (kidsAge == null) return true
    if (mode == "live") return true
    val allowed = allowedCertsForAge(kidsAge, config) ?: return true
    val cert = posterCacheGet(itemId)?.usCert ?: return false
    return cert in allowed
}
