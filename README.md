# Nexorith Browser

Android browser by **NexorithLab** — *Browse Freely. Without Limits.*

- Package: `com.nexorith.browser`
- Engine: Android WebView
- UI: Kotlin + Jetpack Compose (Material 3, dark theme)
- Min SDK 26 · Target SDK 35 · JDK 17
- Repository: `SenzaProject/NexorithBrowser`

## Status

None of the code below has been compiled or run on a device yet; the first GitHub Actions run is the first real build. Statuses describe the code that was written, not verified behaviour.

| Feature | Status | Notes |
|---|---|---|
| Project setup, MVVM structure, CI | IMPLEMENTED | Stage 1 |
| Branding, theme, Home, menu, ID/EN strings | IMPLEMENTED | Stage 2. Logo is a vector from `assets/nexorith-logo.svg`. Text colours and status-bar icons fixed to stay readable on the dark background |
| WebView browsing | IMPLEMENTED | Stage 3. Google is the default search engine. HTTPS default, back/forward/reload/stop, error pages, SSL errors never bypassed, camera/mic/location prompts |
| File upload chooser, fullscreen video, downloads | NOT IMPLEMENTED | Downloads come in stage 7 |
| Multi-tab, bookmarks, history | IMPLEMENTED | Stage 4. SQLite storage. Open tabs are not restored after the app process is killed |
| AdBlock | NOT IMPLEMENTED | Shown as "Upcoming" |
| Incognito | IMPLEMENTED | Stage 6. Separate WebView profile per private session (needs a WebView with multi-profile support; otherwise unavailable, never falls back to the normal profile). No history, profile deleted when the last incognito tab closes. See `docs/TEST-INCOGNITO.md` |
| Download manager / accelerator | NOT IMPLEMENTED | Shown as "Upcoming" |
| VPN (needs a real backend server) | NOT IMPLEMENTED | Shown as "Upcoming" |
| Settings screen | NOT IMPLEMENTED | |

See `docs/NexorithBrowser-Roadmap.md` for the 12-stage plan and `docs/ARCHITECTURE.md` for the code structure.

## Build

GitHub Actions (recommended): push to `main`, open **Actions → Android CI**, then download the `NexorithBrowser-debug` artifact.

Locally (JDK 17 + Android SDK):

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Git from Termux

```bash
pkg update && pkg install git
git clone https://github.com/SenzaProject/NexorithBrowser.git
cd NexorithBrowser
# copy the project files here, then:
git add .
git commit -m "Stage 1: project setup"
git push origin main
```

GitHub no longer accepts account passwords for `git push`; use a Personal Access Token (or `gh auth login`). Never commit tokens, keystores or passwords.

## Troubleshooting

- `Permission denied: ./gradlew` → `chmod +x gradlew` and commit the executable bit (`git update-index --chmod=+x gradlew`).
- CI cannot resolve Gradle/AGP versions → check `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`.
