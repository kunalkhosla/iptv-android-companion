# Khouch Potato — Android TV

Native Android TV client for **[Khouch Potato](https://github.com/kunalkhosla/iptv-webui)**,
a self-hosted Xtream-Codes IPTV streaming server.

Built with **Kotlin + Jetpack Compose for TV + ExoPlayer (media3)**.
This app is a thin presentation client — auth, profiles, state, transcoding,
TMDB enrichment, and concurrency safety all live on the backend. The TV
app simply calls the same `/api/*` endpoints the web client uses, so
favorites, my list, recently played, watched, progress, and last episode
sync automatically across the TV and any browser logged into the same
profile.

> **Pairs with the [`iptv-webui`](https://github.com/kunalkhosla/iptv-webui) repo.**
> You need a reachable iptv-webui deployment to use this app — the URL is
> entered on first launch. There's no standalone mode.

## Features

- **Live, Movies, Series** browse modes — Netflix-style hero + rails for
  Movies and Series, time-grid TV Guide for Live
- **TV Guide** — full-week EPG, live now-line, filter chips (4K / Movies /
  Sports / News / Music / Kids / Entertainment + the languages from your
  onboarded filter), multi-select with AND semantics
- **Chip strip on Movies / Series** — same filter language as the TV Guide,
  applied across the whole home view (Continue Watching, My List, Favorites,
  Recents, plus every category rail)
- **Cross-device state** — play a movie on the web, resume on the TV.
  Favorites, My List, Watched, last episode, and progress all live on the
  server keyed per profile
- **Kids profile** with TMDB-cert age gating
- **ExoPlayer** with automatic transcode fallback when the panel's codec
  isn't browser-friendly
- **Remote-first UX** — DPAD CENTER toggles play/pause + surfaces the
  controller, FF / REW cycle 1× → 1.5× → 2× → 3× with a speed overlay
  pill, **CH UP / DOWN** walks the live channel list (wraparound),
  BACK while scrolled scrolls to top before exiting, BACK from player
  is instant (codec teardown deferred to next frame)
- **Robust audio** — explicitly requests audio focus from Google TV's
  launcher (otherwise it can silence playback), and auto-falls-back
  to the server transcoder when the panel feed uses an audio codec
  the Chromecast can't decode in hardware (AC3 / E-AC3 / MP1)
- **Search** across the full catalog (live + movie + series) in one screen

## Status

Production daily-driver on a Chromecast with Google TV. Tested on
Sabrina (armeabi-v7a, ~2 GB RAM); should work on any Android TV running
API 26+ with reasonable hardware.

## Building

The Gradle wrapper is committed, so after cloning you just need a JDK +
the Android SDK:

```bash
# install Android Studio (easiest) — bundles JDK 17 + SDK + ADB
brew install --cask android-studio

# OR headless: install JDK 17 + the Android command-line tools
brew install openjdk@17
brew install --cask android-commandlinetools

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# accept SDK licenses, install platform 34 + build-tools
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# build
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`
(~24 MB, package `com.khouch.tv.debug`).

## Sideloading to your Android TV

```bash
# enable Developer Options on the TV (Settings → System → About → tap Build 7 times)
# enable "USB debugging" / "Network debugging" / "Wireless debugging"

adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app appears in the Android TV launcher under your apps row.

## First-run flow

1. **Server URL** — point it at your `iptv-webui` deploy (e.g.
   `https://your-iptv-webui.example.com`). Stored in DataStore so
   subsequent launches skip this step.
2. **Login** — uses the same `APP_USER` / `APP_PASS` the web client uses.
3. **Profile picker** — pick from existing profiles. New profiles are
   created via the web client's settings UI.
4. **Browse + play** — Live / Movies / Series.

Cookies persist for 30 days. After the first login + profile pick, the
app lands straight on the browse screen on subsequent launches.

## Pairing with `iptv-webui`

This repo only ships the client. You must run an `iptv-webui` instance
that the TV can reach over HTTPS (or HTTP for LAN-only setups).

When the server's `/api/*` route shape or `userState` schema changes in
`iptv-webui`, this repo's Retrofit `KhouchApi` interface and the Kotlin
data classes in `data/model/` MUST be updated in the same PR. See
`CLAUDE.md` for the contract details.

## Layout

```
app/src/main/kotlin/com/khouch/tv/
├── KhouchApp.kt              Application + Koin start
├── MainActivity.kt           Single Activity, Compose root
├── nav/                      Destinations + NavGraph
├── data/
│   ├── api/                  Retrofit interfaces (KhouchApi)
│   ├── auth/                 PersistentCookieJar (DataStore-backed)
│   ├── model/                Kotlin data classes mirroring server JSON
│   ├── prefs/                Server URL + user prefs (DataStore)
│   └── repo/                 KhouchRepository
├── di/                       Koin modules
└── ui/
    ├── theme/                KhouchTheme + KhouchColors
    ├── common/               Focus helpers, GROUPS, PanelImage
    ├── login/                ServerUrl + Login screens
    ├── profile/              ProfilePicker
    ├── main/                 MainScreen (mode tabs + content swap)
    ├── home/                 HomeRails (Movies / Series rails + chip strip)
    ├── guide/                TvGuideScreen (Live time grid + chip strip)
    ├── detail/               Movie / Series detail
    ├── search/               SearchScreen
    ├── settings/             SettingsScreen
    └── player/               PlayerScreen (ExoPlayer surface + key handler)
```

## Related

- Backend / web client: [iptv-webui](https://github.com/kunalkhosla/iptv-webui)
- Working conventions: `CLAUDE.md`
