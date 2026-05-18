# CLAUDE.md

Working notes and conventions for this repo. Treat as ground truth
before making changes.

## What this is

Native Android TV client for **Khouch Potato**, a self-hosted Xtream-Codes
IPTV streaming app. Companion to [`iptv-webui`](https://github.com/kunalkhosla/iptv-webui),
which is both the backend (Node + Express) and the web client.

**The TV app is a thin client.** It does NOT duplicate the panel protocol,
transcoding, TMDB enrichment, profile auth, concurrency-cap, or filter
regex tables — those all live on the server. The TV app just calls
`/api/*` and renders.

## Companion repo: iptv-webui

The backend lives at [`github.com/kunalkhosla/iptv-webui`](https://github.com/kunalkhosla/iptv-webui).
The same Express server is consumed by both the web client (HTML/CSS/JS
in `iptv-webui/public/`) and this TV app. There is no production URL
baked into either repo — every install points at its own iptv-webui
deployment via the first-run **Server URL** screen.

**Hard rule:** when the server's `/api/*` route shape, auth flow, cookie
attributes, or `userState` schema changes in `iptv-webui`, this repo's
Retrofit `KhouchApi` interface and Kotlin models MUST be updated in the
same PR. CLAUDE.md in both repos should always stay in sync — if a
contract changes there, update both files. The web repo's CLAUDE.md has
a matching "Companion: Android TV client" section.

## Stack at a glance

- **Language:** Kotlin 2.x
- **UI:** Jetpack Compose for TV (`androidx.tv:tv-foundation`,
  `androidx.tv:tv-material`)
- **Playback:** `androidx.media3` (ExoPlayer)
- **Networking:** Retrofit 2.x + OkHttp 4.x + `PersistentCookieJar`
- **Images:** Coil 3.x (hardware bitmaps, 12% memory cap, no crossfade —
  see KhouchApp.newImageLoader)
- **DI:** Koin 4.x
- **Local prefs:** DataStore Preferences
- **Min SDK:** 26 (Android 8.0); target / compile SDK 34
- **Single-activity, single-module.** Compose only — no XML layouts.

## Auth model

There is **one** auth flow, identical to the web client:

1. App holds a server URL in DataStore (empty by default; entered on the
   ServerUrlScreen at first launch).
2. `POST /api/login` with username + password sets the `khouch_session`
   cookie. OkHttp's CookieJar captures it (HttpOnly is a browser-only
   restriction). 30-day Max-Age.
3. `POST /api/profile/select` sets `khouch_profile` cookie.
4. Both cookies are persisted to DataStore by `PersistentCookieJar` so
   they survive process death.

There is NO separate Android-side credential storage. The cookie IS the
credential.

## State model

| State | Where it lives | Persistence |
|---|---|---|
| Server URL | DataStore (`UserPrefs`) | Across restarts |
| Cookies (`khouch_session`, `khouch_profile`) | DataStore via `PersistentCookieJar` | Across restarts (30 days) |
| Profile catalog (favorites, my list, recents, watched, progress, lastEpisode, filter) | **Server-side**, in `data/user-state.json` per profile, accessed via `/api/bootstrap` + endpoints in iptv-webui | Across restarts AND across devices |
| Current mode / chip filters / scroll positions / hero rotation index | In-memory ViewModel | Process lifetime only |

**Important consequence:** play a movie on the web client, see the same
"Continue Watching" entry on the TV app, and vice versa. We must NEVER
persist a separate copy of `userState` locally — that's how clients drift.

## Server endpoints consumed

| Endpoint | Purpose |
|---|---|
| `GET /healthz` | Probe server reachability before showing login |
| `POST /api/login` | Username / password → `khouch_session` cookie |
| `POST /api/logout` | Clear session cookie on sign-out |
| `GET /api/profiles` | List profiles for the picker |
| `POST /api/profile/select` | Set `khouch_profile` cookie |
| `GET /api/bootstrap` | Categories + index status + active profile + userState |
| `GET /api/index/{live,movie,series}` | Full per-mode catalog incl. pre-computed `tags` |
| `GET /api/home/{movie,series}` | Server-built hero + rails for Netflix-style home |
| `GET /api/movie/info/{id}` | Movie detail |
| `GET /api/series/info/{id}` | Series detail incl. seasons / episodes |
| `GET /api/poster/{mode}/{id}` | TMDB enrichment (poster, backdrop, plot, rating, us_cert) |
| `GET /api/poster/series/{id}/season/{n}` | Per-episode stills |
| `GET /api/stream/{mode}/{id}.{ext}` | `{ direct, proxy, transcode }` URLs |
| `GET /api/search/{mode}?q=…` | Substring catalog search (returns `{ q, count, results }`) |
| `GET /api/epg/short/{streamId}` | EPG window (~1h back to 8h forward) for one channel |
| `POST /api/play-event/{mode}/{id}` | Stamp lastPlayed timestamp |
| `POST /api/progress/{mode}/{id}` | Resume position for movie / series episode |
| `PUT /api/user-state` | Whole-state push for cross-device sync |

## Server-side filter tags

Every stream in `/api/index/{mode}` and every tile in `/api/home/{mode}`
arrives with a `tags: string[]` array — pre-computed at index-build time
by the server's `CHANNEL_GROUPS` regex table + the regional-default and
XX:-prefix layers. The chip-toggle hot path on both clients is just a
Set membership check against `tags`, NOT a regex pass per item. This is
what lets the TV filter 10k channels with 3 chips selected without
ANR-killing the app.

Local `CategoryTags` / `buildCategoryTags` / hardcoded `GROUPS` array in
`ui/common/GroupFilters.kt` is a fallback for servers that predate the
tagging system. New code should always prefer reading `stream.tags` /
`homeItem.tags` directly, going through `effectiveTagsFor(stream, …)` /
`matchesChipKey(stream, key, …)` so the fallback is transparent.

## Server-driven chip catalog + kids-cert thresholds (`filterConfig`)

`/api/bootstrap` returns a `filterConfig` payload:

```
filterConfig: {
  groups: [{key, label, kind}, ...],
  syntheticTags: ["4k", "movies", "entertainment"],
  nonEntertainmentTags: [...],
  kidsCertTiers: [{minAge, add: [...]}],
}
```

This is the **source of truth** for which chip buckets exist (key +
label + kind) and which US certs are allowed at a given kid age.
`KhouchRepository.filterConfig: StateFlow<FilterConfig?>` exposes it;
`chipCatalog(config)` / `chipLabelFor(key, config)` in `GroupFilters.kt`
prefer it over the hardcoded fallback; `allowedCertsForAge(age, config)`
in `KidsFilter.kt` does the same for kids profiles. As long as the
deployed server is up-to-date, adding a new language / region / genre
or shifting a cert threshold requires **no APK update** — clients pick
the new config up on the next bootstrap. The hardcoded fallback only
kicks in if the deployed server is older than the APK.

## Stream playback flow

Identical to the web client's logic, ported to ExoPlayer:

1. User picks a stream.
2. App calls `GET /api/stream/{mode}/{id}.{ext}` → returns
   `{ direct, proxy, transcode }` URLs (signed for proxy / transcode).
3. ExoPlayer first tries `direct` (panel URL, no transcode cost).
4. On `PlaybackException` or HLS manifest parse error, fall back to the
   signed `transcode` URL.
5. On exit (and best-effort at intermediate points), `POST /api/progress/{mode}/{id}`
   with position so cross-device resume works.
6. Audio focus is explicitly requested (`USAGE_MEDIA` /
   `CONTENT_TYPE_MOVIE`, `handleAudioFocus=true`). Without this, Google
   TV's launcher can silence playback.
7. **Audio codec fallback.** Many Indian-panel channels broadcast AC3 /
   E-AC3 / MP1 audio. The Chromecast's hardware MediaCodec doesn't
   decode those, but ExoPlayer doesn't raise a `PlaybackException` —
   video plays fine, audio is silently dropped. The `onTracksChanged`
   listener checks if any audio group reports `isTrackSupported()`;
   if none do, falls back to the server's transcoder which always
   re-encodes to AAC 192k. Single-shot via `triedTranscode` so it
   can't loop.
8. **Player teardown is deferred to the next main-thread frame.** The
   DisposableEffect's onDispose posts `exo.stop() / clearMediaItems() /
   release()` to `Handler(Looper.getMainLooper())` so navigation pops
   first and the heavy ~200–500 ms codec / surface teardown doesn't
   stutter the nav exit animation. ExoPlayer must stay on the main
   thread; can't move it to a worker.

## Player remote handling

- **DPAD CENTER / ENTER / MEDIA_PLAY_PAUSE** — toggle play/pause + show
  controller. Caught at the Compose `onPreviewKeyEvent` layer (not the
  PlayerView's own setOnKeyListener) so it fires regardless of which
  inner controller child holds focus.
- **MEDIA_FAST_FORWARD** — cycle 1× → 1.5× → 2× → 3× → 1×. Speed pill
  overlay surfaces while non-1×.
- **MEDIA_REWIND** — cycle down through the same set.
- **CHANNEL_UP / CHANNEL_DOWN** — walk the live channel list with
  wraparound (live mode only). `streamId` is mutable on the VM so a
  channel hop re-resolves without recreating the VM or popping nav.
- **BACK** — pop navigation. We bypass PlayerView's "first BACK hides
  the controller" default so the first BACK pops immediately.

## Compose-for-TV text fields (login + server URL)

Compose-for-TV's `BasicTextField` does NOT reliably route DPAD_DOWN
to a sibling `Button` on the same screen — the on-screen IME captures
the key. **Always wire `KeyboardActions` for every text field**:

- Username / mid-form fields: `imeAction = Next` + `KeyboardActions(onNext = { passFocus.requestFocus() })`
- Terminal fields: `imeAction = Done` + `KeyboardActions(onDone = { submit() })`

The IME ✓ / →| key is the only reliable dismount path. D-pad to the
button is a nice-to-have on top. ServerUrlScreen and LoginScreen both
have this wiring; copy the pattern for any new text-field screen.

## Kids profile

The kids filter rules live on the server (TMDB `us_cert` + age buckets).
The TV app reads `kidsBirthYear` from the profile and applies the same
allowed-cert sets the web client uses (in `iptv-webui/public/app.js`'s
`allowedCertsForAge`). The Kotlin implementation must stay byte-for-byte
equivalent in the buckets it produces.

## Profile switch flow

`repo.selectProfile(id)` on the Android side does three things:
1. POST `/api/profile/select` (server sets cookie)
2. **Clear all in-memory StateFlows** (favorites, recents, progress,
   profile, byCategory, userState) so the next render reflects the
   new profile
3. Re-fetch `bootstrap()` to repopulate

Without step 2 the ViewModel layer keeps serving the previous profile's
"Continue Watching" after a switch even though the cookie did flip.
The server's home endpoint also has a cross-language title guard
(`titleLangPasses`) that drops items whose title names a language the
profile didn't onboard — see iptv-webui CLAUDE.md → "Home filtering
rules".

## TV-specific perf notes

The Chromecast with Google TV is a Sabrina device (armeabi-v7a, ~2 GB
RAM). A few invariants that came out of profiling:

- **Hardware bitmaps + memory cap.** `KhouchApp.newImageLoader` sets
  Coil to use hardware bitmaps and caps memory at 12% of the heap. The
  initial defaults caused GC churn of ~24 MB freed per cycle while
  scrolling Movies.
- **Smaller posters.** Tiles use `w154` from TMDB (not `w185`) because
  the 140-dp tile decodes more cheaply at exactly its size.
- **Progressive rail reveal.** `HomeRails` shows the first 5 rails
  immediately and unfolds the rest at 250 ms via `LaunchedEffect`. The
  hero backdrop is deferred 200 ms. Both keep the first-frame composition
  cheap enough to fit a vsync window.
- **No `Modifier.focusRestorer()` inside the rails.** Compose Foundation
  1.7 throws "Release should only be called once" on mode switch
  (Movies → Series) when a `LazyLayoutPinnableItem` is detached mid-flight.
- **Card colors memoized at module scope.** See `ui/common/Focus.kt`.
- **No card scale on focus.** Triggers a layout pass per focus change.
  Border-only focus indication suffices.
- **Progress writes detach to a process-lifetime CoroutineScope.** A
  `viewModelScope` write gets cancelled the moment BACK pops the
  player; that's why early resume positions weren't persisting.

## Things NOT to do

- **Don't bundle the Node server in the APK.** This is a client.
- **Don't store profile state locally.** Always fetch from
  `/api/bootstrap` and write through endpoints. The server is the
  source of truth.
- **Don't add a separate Android username/password.** The cookie
  session is the only credential.
- **Don't introduce a build step on the web side.** That's an
  iptv-webui constraint and it carries forward.
- **Don't fork the panel logic.** Anything that needs to talk to the
  Xtream-Codes panel goes through `iptv-webui/server.js`.
- **Don't hardcode a server URL.** Empty `DEFAULT_SERVER_URL` keeps the
  build generic; every install points at its own iptv-webui.

## Working on this code

- **No comments unless WHY is non-obvious.** Same convention as iptv-webui.
- **No backwards-compat shims.** Rename / remove, don't decorate with
  "// removed X" markers.
- **Verify on real TV hardware** before reporting done — the emulator's
  D-pad behavior diverges from real Android TV remotes, and Coil + GC
  measurements only tell the truth on the actual silicon.
- **Compose previews are nice-to-have, not required.** TV layouts often
  look fine in preview and feel broken on a real D-pad — so always do a
  real-device check.

## File map

```
app/src/main/kotlin/com/khouch/tv/
├── KhouchApp.kt              Application + Koin start + Coil config
├── MainActivity.kt           Single Activity, Compose entry
├── nav/                      Destinations + NavGraph
├── data/
│   ├── api/
│   │   ├── KhouchApi.kt      Retrofit interface (all endpoints)
│   │   └── ApiFactory.kt     OkHttp + Retrofit construction (dynamic base URL)
│   ├── auth/
│   │   └── PersistentCookieJar.kt
│   ├── model/
│   │   └── Models.kt         All data classes mirroring server JSON
│   ├── prefs/
│   │   └── UserPrefs.kt
│   └── repo/
│       └── KhouchRepository.kt   process-lifetime ioScope for detached writes
├── di/
│   └── AppModule.kt          Koin module
└── ui/
    ├── theme/                KhouchColors, KhouchTheme
    ├── common/
    │   ├── Focus.kt          Memoized borders + KhouchCardBorder
    │   ├── GroupFilters.kt   GROUPS regex fallback + CategoryTags
    │   └── PanelImage.kt     Coil wrapper with initials fallback
    ├── login/                ServerUrl + Login screens
    ├── profile/              ProfilePicker
    ├── main/                 MainScreen (mode tabs + content swap)
    ├── home/                 HomeRails (Movies / Series rails + chip strip)
    ├── guide/                TvGuideScreen (Live time grid + chip strip)
    ├── detail/               Movie / Series detail
    ├── search/               SearchScreen
    ├── settings/             SettingsScreen
    └── player/               PlayerScreen (ExoPlayer + Compose key handler)
```
