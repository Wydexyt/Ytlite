<div align="center">

# YT Lite

**A lightweight, battery-friendly YouTube client for Android**

[![Latest release](https://img.shields.io/github/v/release/Wydexyt/Ytlite?label=download&color=e8304a)](https://github.com/Wydexyt/Ytlite/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/Wydexyt/Ytlite/build.yml?label=build)](https://github.com/Wydexyt/Ytlite/actions)
[![License](https://img.shields.io/github/license/Wydexyt/Ytlite)](LICENSE)
![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)

English · [Русский](README.md)

</div>

## Description

YT Lite is an unofficial YouTube client for Android with account sign-in: home, subscriptions, history, search, channels, comments, likes and subscribing all work as usual. The main difference from the official app is that it's built to use as little battery and data as possible.

> The app interface is in Russian.

**How it differs from the official app**
- **Only audio plays in the background.** As soon as the player screen is closed, video stops being downloaded and decoded. The "audio only" button enables this mode even with the screen open — handy for podcasts and music.
- **The modem isn't kept busy.** Audio is buffered several minutes ahead, then the modem goes to sleep instead of holding the connection open.
- **Separate quality for Wi-Fi and mobile data** — e.g. 1080p at home and 480p on the go, no manual switching.
- **Skips creators' sponsor segments** via [SponsorBlock](https://sponsor.ajay.app).
- **Nothing runs in the background** while you're not watching: no background syncs, prefetching or notifications.
- **Clean feeds:** no Shorts, live streams or premieres.
- **Small app** — about 3 MB versus well over a hundred for the official one.

**What's missing**
- Shorts, live streams, video downloads, playlists and Watch Later (for now).
- Russian-only interface.

## How it saves battery

| What it does | Why |
|---|---|
| Player screen closed → video track disabled | video is neither downloaded nor decoded |
| AAC audio + audio offload | the audio DSP decodes sound while the CPU sleeps |
| Up to 10-minute buffer | the modem downloads a large chunk and then sleeps for long |
| No background services or syncs | the app does nothing when you're not using it |

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
