# Nexorith Browser

Android browser by **NexorithLab** — *Browse Freely. Without Limits.*

- Package: `com.nexorith.browser`
- Engine: Android WebView
- UI: Kotlin + Jetpack Compose (Material 3, dark theme)
- Min SDK 26 · Target SDK 35 · JDK 17
- Repository: `SenzaProject/NexorithBrowser`

## Status

| Feature | Status |
|---|---|
| Project setup, MVVM structure, CI | IMPLEMENTED |
| Branding and UI | NOT IMPLEMENTED |
| WebView browsing | NOT IMPLEMENTED |
| Multi-tab, bookmarks, history | NOT IMPLEMENTED |
| AdBlock | NOT IMPLEMENTED |
| Incognito | NOT IMPLEMENTED |
| Download manager / accelerator | NOT IMPLEMENTED |
| VPN (needs a real backend server) | NOT IMPLEMENTED |

The CI pipeline has been written but not yet run on GitHub. See `docs/NexorithBrowser-Roadmap.md` for the 12-stage plan and `docs/ARCHITECTURE.md` for the code structure.

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
