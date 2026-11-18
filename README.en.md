<div align="center">

# YT Lite

**A lightweight, battery-friendly YouTube client for Android**

[![Latest release](https://img.shields.io/github/v/release/Wydexyt/Ytlite?label=download&color=e8304a)](https://github.com/Wydexyt/Ytlite/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/Wydexyt/Ytlite/build.yml?label=build)](https://github.com/Wydexyt/Ytlite/actions)
[![License](https://img.shields.io/github/license/Wydexyt/Ytlite)](LICENSE)
![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)

English · [Русский](README.md)

</div>

## What it is

YT Lite is an unofficial YouTube client with account sign-in. Its main goal is minimal battery and data usage: in the background only audio plays, video isn't downloaded, and the modem isn't kept awake for nothing.

> The app interface is in Russian.

## Features

**Feeds and search**
- Home (recommendations), subscriptions and history — swipe between tabs, scroll position is kept
- Search with suggestions and history, channels in results
- Channel pages, subscribe / unsubscribe
- Multiple accounts with switching

**Player**
- Audio-only in the background, plus an "audio only" button while the screen is open
- Double-tap to seek 5 s (each extra tap adds 5 s)
- Swipe down to minimize into a mini player; brightness and volume swipes in fullscreen
- Chapters on the seek bar, clickable timestamps
- Resume where you left off
- Skips in-video sponsor segments via [SponsorBlock](https://sponsor.ajay.app)
- Separate default quality for Wi-Fi and mobile data

**Below the video**
- Description, like/dislike, comments with replies, posting comments
- Related videos

**Other**
- Opens YouTube links from other apps and the share menu
- Checks for updates by itself

## How it saves battery

| What it does | Why |
|---|---|
| Player screen closed → video track disabled | video is neither downloaded nor decoded |
| AAC audio + audio offload | the audio DSP decodes sound while the CPU sleeps |
| Up to 10-minute buffer | the modem downloads a large chunk and then sleeps for long |
| No background services or syncs | the app does nothing when you're not using it |

## Install

1. Download the APK from the [latest release](https://github.com/Wydexyt/Ytlite/releases/latest) and install it (allow installs from this source).
2. **Xiaomi / HyperOS:** app settings → "Battery saver" → "No restrictions", otherwise the system will kill background playback.
3. **To open YouTube links in YT Lite:** app settings → "Open by default" → enable "Open supported links" and tick the addresses. If the official YouTube app is installed, disable the same option there.

Updates install on top; your sign-in is kept.

## Building

Every push to `main` is built by GitHub Actions and published as a release. Locally:

```bash
./gradlew assembleRelease
```

Signing with the permanent key needs the repository secrets `KEYSTORE_B64` and `KEYSTORE_PASSWORD`; without them a local build is signed with the debug key.

## If something breaks

YouTube regularly changes how video streams are protected. If videos stop opening, updating [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor/releases) in `app/build.gradle.kts` usually helps. Report problems via [Issues](https://github.com/Wydexyt/Ytlite/issues).

Roadmap: [docs/ROADMAP.md](docs/ROADMAP.md). Changes: [CHANGELOG.md](CHANGELOG.md) (in Russian).

## Credits

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — stream extraction
- [AndroidX Media3](https://github.com/androidx/media) — player
- [SponsorBlock](https://sponsor.ajay.app) — sponsor segment data
- [Coil](https://github.com/coil-kt/coil), [OkHttp](https://github.com/square/okhttp)

## License and disclaimer

Licensed under [GPL-3.0](LICENSE).

YT Lite is an unofficial project, not affiliated with or endorsed by Google or YouTube. YouTube is a trademark of Google LLC. Using a third-party client may violate YouTube's Terms of Service; use at your own risk.
