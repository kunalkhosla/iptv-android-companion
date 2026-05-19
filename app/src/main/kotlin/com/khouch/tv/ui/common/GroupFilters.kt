package com.khouch.tv.ui.common

import com.khouch.tv.data.model.FilterConfig
import com.khouch.tv.data.model.FilterGroup
import com.khouch.tv.data.model.Stream

// Direct port of public/app.js GROUPS. Same keys, same labels, same
// regex patterns. Kept as a **fallback** for older servers that don't
// send `filterConfig` in /api/bootstrap. When the server does send
// it, the catalog below is bypassed entirely — adding a language /
// region / genre is then a server-only change with zero APK update.

data class Group(val key: String, val label: String, val patterns: List<Regex>)

private fun re(vararg patterns: String): List<Regex> =
    patterns.map { Regex(it, RegexOption.IGNORE_CASE) }

val GROUPS: List<Group> = listOf(
    // Languages
    Group("english", "English", re(
        """\benglish\b""", """\bblockbuster\b""", """\boscar\b""",
    )),
    Group("hindi", "Hindi", re(
        """\bhindi\b""", """bollywood""",
        """\bstar plus\b""", """\bstar bharat\b""", """\bzee tv\b""", """\bcolors hindi\b""",
        """\bsony \(set\)\b""", """\bsab\b""", """\band tv\b""", """\bmtv hindi\b""", """\bepic tv\b""",
        """\bsony liv\b""", """\bdisney.*hotstar\b""", """\bzee5\b""", """\bjio cinema\b""",
        """\bvoot\b""", """\bmx player\b""", """\bhungama play\b""",
        """\btvf\b""", """\bullu\b""", """\beros now\b""", """\bjio\b""",
        """\baandetv\b""", """\bbigg boss\b""", """shemaroo""", """\bhangama\b""",
        """\baddatimes\b""", """\bgreen tv\b""", """\bsony aath\b""", """amazon mini\b""",
        """\bwaves ott\b""", """\bsaregama\b""", """lionsgate play""",
        """\bgemplex\b""", """\bnews nation\b""",
    )),
    Group("punjabi", "Punjabi", re("""punjabi""")),
    Group("tamil", "Tamil", re(
        """\btamil\b""", """\bstar vijay\b""", """\bsun tamil\b""", """\bzee tamil\b""",
    )),
    Group("telugu", "Telugu", re(
        """\btelugu\b""", """\bgemini\b""", """\bstar maa\b""", """\bzee telugu\b""", """\betv\b""", """\baha\b""",
    )),
    Group("malayalam", "Malayalam", re("""malayalam""", """asianet""", """\bsurya\b""")),
    Group("kannada", "Kannada", re("""kannada""", """star suvarna""")),
    Group("marathi", "Marathi", re("""marathi""", """star pravah""")),
    Group("gujarati", "Gujarati", re("""gujarati""")),
    Group("bengali", "Bengali", re("""\bbangla\b""", """bengali""", """jalsha""")),
    Group("urdu", "Urdu", re("""\burdu\b""")),
    Group("arabic", "Arabic", re("""arabic""", """\bbein\b""", """\bmbc\b""")),
    // Countries
    Group("us", "USA", re(
        """\busa?\b""", """america""",
        """\bnfl\b""", """\bmlb\b""", """\bnba\b""", """\bmls\b""", """\bnhl\b""",
        """netflix""", """\bhbo\b""", """amazon prime""", """\bdisney\b""", """starz""", """\bhulu\b""", """\bpeacock\b""",
    )),
    Group("india", "India", re(
        """\bindia\b""", """\bindian\b""", """\bipl\b""", """\bhub premier\b""", """cricket""",
    )),
    Group("pakistan", "Pakistan", re(
        """pakistan""", """\bptv\b""", """\bary\b""", """\bgeo\b""", """\bhum tv\b""",
        """\bexpress tv\b""", """aplus""", """\baan\b""", """aur life""", """play entertainment""",
        """\bmun tv\b""", """\btv one\b""", """apna""", """kashmir""", """dunya""", """\bsamaa\b""",
        """\burdu\b""", """cricket""", """\bpsl\b""",
    )),
    Group("uk", "UK", re("""\buk\b""", """\bbritish\b""", """\bbbc\b""", """sky uk""")),
    Group("canada", "Canada", re("""canada""", """canadian""", """\bctv\b""")),
    Group("australia", "Australia", re("""australia""", """australian""", """fox australia""", """\bdstv\b""")),
    // Genre catch-alls (in addition to the universal Sports/Kids/News chips)
    Group("sports", "Sports", re(
        """\bsports?\b""", """cricket""", """football""", """soccer""", """tennis""", """\bgolf\b""",
        """rugby""", """racing""", """\bf1\b""", """motogp""", """\bnfl\b""", """\bmlb\b""", """\bnba\b""",
        """\bmls\b""", """\bnhl\b""", """\bepl\b""", """\bipl\b""", """\bpsl\b""",
        """world cup""", """\bfifa\b""", """\bufc\b""", """boxing""", """wrestling""", """\bwwe\b""",
    )),
    Group("kids", "Kids", re(
        """\bkids\b""", """cartoon""", """\bcbeebies\b""", """nickelodeon""", """\bnick jr\b""",
        """\bbaby\b""", """\btoddler\b""",
    )),
    Group("news", "News", re("""\bnews\b""")),
    Group("music", "Music", re(
        """\bmusic\b""", """\bmtv\b""", """\bvh1\b""", """\bvevo\b""", """\bmusik\b""",
        """\b9xm\b""", """\bb4u music\b""", """\bsangeet\b""",
    )),
)

// Groups by key for fast lookup.
val GROUPS_BY_KEY: Map<String, Group> = GROUPS.associateBy { it.key }

// Returns the set of group keys that match a category name.
fun groupKeysOf(categoryName: String): List<String> {
    if (categoryName.isBlank()) return listOf("other")
    val out = mutableListOf<String>()
    for (g in GROUPS) {
        if (g.patterns.any { it.containsMatchIn(categoryName) }) out += g.key
    }
    return out.ifEmpty { listOf("other") }
}

// Synthesized "movies" pattern set — guide-only, not part of GROUPS.
// Matches the web's GUIDE_SYNTHETIC_PATTERNS.movies.
val MOVIES_PATTERNS: List<Regex> = re("""\bmovies?\b""", """\bcinema\b""")

// Synthesized "4K" pattern set — catches anything with explicit
// resolution markers in the category name: 4K, UHD, 2160p variants.
val FOURK_PATTERNS: List<Regex> = re(
    """\b4k\b""",
    """\buhd\b""",
    """\b2160p?\b""",
    """\(2160\)""",
)

private val NON_ENTERTAINMENT_KEYS = setOf("sports", "news", "kids", "music", "movies")

// Returns true if the channel's category name matches the given chip key.
// Matches the web's channelMatchesQuickFilter.
fun matchesChipKey(key: String, categoryName: String): Boolean {
    if (key == "all") return true
    if (key == "movies") return MOVIES_PATTERNS.any { it.containsMatchIn(categoryName) }
    if (key == "4k") return FOURK_PATTERNS.any { it.containsMatchIn(categoryName) }
    if (key == "entertainment") {
        if (MOVIES_PATTERNS.any { it.containsMatchIn(categoryName) }) return false
        val keys = groupKeysOf(categoryName)
        return !keys.any { it in NON_ENTERTAINMENT_KEYS }
    }
    val g = GROUPS_BY_KEY[key] ?: return false
    return g.patterns.any { it.containsMatchIn(categoryName) }
}

// Pre-computed tag bundle for a single category. Built once per
// category name (cheap regex pass) so the chip-toggle hot path can be
// O(1) set lookups instead of re-running every GROUPS regex against
// every channel's category name on the UI thread.
class CategoryTags(
    val keys: Set<String>,
    val is4k: Boolean,
    val isMovies: Boolean,
) {
    fun matches(chipKey: String): Boolean = when (chipKey) {
        "all" -> true
        "4k" -> is4k
        "movies" -> isMovies
        "entertainment" -> !isMovies && keys.none { it in NON_ENTERTAINMENT_KEYS_PUBLIC }
        else -> chipKey in keys
    }
}

internal val NON_ENTERTAINMENT_KEYS_PUBLIC = NON_ENTERTAINMENT_KEYS

fun buildCategoryTags(categoryName: String): CategoryTags {
    val keys = groupKeysOf(categoryName).toSet()
    val is4k = FOURK_PATTERNS.any { it.containsMatchIn(categoryName) }
    val isMovies = MOVIES_PATTERNS.any { it.containsMatchIn(categoryName) }
    return CategoryTags(keys = keys, is4k = is4k, isMovies = isMovies)
}

// Detect which groups have at least one matching channel in the catalog,
// preserving GROUPS order. Used to show only chips that actually have
// content behind them.
fun detectGroupsForCategories(categoryNames: List<String>): List<Group> {
    val counts = HashMap<String, Int>()
    for (name in categoryNames) {
        for (key in groupKeysOf(name)) counts[key] = (counts[key] ?: 0) + 1
    }
    return GROUPS.filter { counts.containsKey(it.key) }
}

// ──────────────────────────────────────────────────────────────────
// Data-driven path. Prefers server-supplied chip catalog + per-stream
// tags; falls back to the hardcoded GROUPS / category regex above
// when either is missing. Once filterConfig is in the bootstrap
// response and streams come back with `tags` populated (server is
// up-to-date), this whole module's hardcoded tables become inert.
// ──────────────────────────────────────────────────────────────────

// The chip catalog the UI should render. Server is authoritative.
fun chipCatalog(config: FilterConfig?): List<FilterGroup> {
    val server = config?.groups
    if (!server.isNullOrEmpty()) return server
    return GROUPS.map { FilterGroup(key = it.key, label = it.label, kind = "other") }
}

// Label lookup with fallback. Used everywhere a chip needs a display
// label that may have come from a key the local GROUPS table doesn't
// know about (a newer server emitting `iranian` / `turkish` / etc.).
fun chipLabelFor(key: String, config: FilterConfig?): String {
    config?.groups?.firstOrNull { it.key == key }?.let { return it.label }
    return GROUPS.firstOrNull { it.key == key }?.label ?: key
}

// Server-attached tag set on a stream is the source of truth. Falls
// back to running category-name regex when tags are missing (old
// server / freshly-loaded index hasn't been re-tagged yet).
fun effectiveTagsFor(stream: Stream, categoryNameLookup: (String?) -> String): Set<String> {
    if (stream.tags.isNotEmpty()) return stream.tags.toSet()
    val name = categoryNameLookup(stream.categoryId)
    val keys = groupKeysOf(name).toMutableSet()
    if (FOURK_PATTERNS.any { it.containsMatchIn(name) }) keys += "4k"
    if (MOVIES_PATTERNS.any { it.containsMatchIn(name) }) keys += "movies"
    if (keys.none { it in NON_ENTERTAINMENT_KEYS }) keys += "entertainment"
    return keys
}

// Tag-driven chip match. `key` is any value the catalog can produce
// — language / country / genre / synthetic (4k, movies, entertainment).
fun matchesChipKey(stream: Stream, key: String, categoryNameLookup: (String?) -> String): Boolean {
    if (key == "all") return true
    return key in effectiveTagsFor(stream, categoryNameLookup)
}

// Returns the catalog entries that have at least one matching stream,
// preserving catalog order. Used by the filter modal so onboarding
// only shows buckets that actually contain content.
fun detectGroupsForStreams(
    streams: List<Stream>,
    config: FilterConfig?,
    categoryNameLookup: (String?) -> String,
): List<FilterGroup> {
    val counts = HashMap<String, Int>()
    for (s in streams) {
        for (t in effectiveTagsFor(s, categoryNameLookup)) {
            counts[t] = (counts[t] ?: 0) + 1
        }
    }
    return chipCatalog(config).filter { counts.containsKey(it.key) }
}
