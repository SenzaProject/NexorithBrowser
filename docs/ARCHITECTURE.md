# Architecture

Pattern: **MVVM** with a repository layer, Kotlin coroutines/Flow, and a hand-written dependency container (`di/AppContainer`). A DI framework can be introduced later if the graph grows.

```
UI (Compose screen) → ViewModel (StateFlow<UiState>) → Repository (interface) → data source
```

## Packages (`com.nexorith.browser`)

| Package | Purpose | Stage |
|---|---|---|
| `di` | `AppContainer`, wiring of repositories | 1 |
| `data` | `AppInfo`, SQLite helper shared by bookmarks/history | 1, 4 |
| `ui.*` | Compose theme, components, Home, browser, tabs, bookmarks, history screens | 2–4 |
| `browser` | `BrowserViewModel` (tab state), `UrlResolver`, `WebViewRegistry` (one WebView per tab), site permission requests | 3–4 |
| `bookmarks`, `history` | Repository interface + SQLite implementation | 4 |
| `adblock` | Filter parser, rule matching, request interception | 5 |
| `incognito` | Isolated profile and session cleanup | 6 |
| `downloads` | Download manager and accelerator | 7–8 |
| `vpn` | `VpnService` client and real backend | 9 |
| `network` | Shared HTTP utilities | as needed |
| `security` | URL validation, TLS policy, secure storage | 3, 10 |
| `settings` | Preferences and screens | as needed |

Empty packages contain only a `.gitkeep` until their stage starts.

## Browser flow

`BrowserViewModel` holds tab state (`TabState`) and never touches a WebView. The UI asks it to load a URL, which sets a one-shot `LoadRequest` on the tab. `BrowserScreen` performs the load on the tab's WebView (owned by `WebViewRegistry`) and tells the ViewModel the request was consumed. WebView callbacks flow back through `WebEvents` into the ViewModel. This keeps tab logic unit-testable without Android.

Security defaults: HTTPS-first address resolution, `javascript:`/`file:`/`content:`/`data:` blocked, cleartext traffic disabled, mixed content blocked, file/content access off, SSL errors cancelled, third-party cookies off, WebView debugging only in debug builds.

Known limits: tabs are not restored after process death; the activity handles configuration changes itself so WebViews survive rotation.

## Conventions

- One ViewModel per screen; UI state is an immutable data class.
- Features are not shown as working until they are. Unavailable features are labelled "Upcoming" / "Akan datang" in the UI, with no stage or progress text.
- No secrets, keystores or tokens in the repository. Release signing uses CI secrets (stage 12).
- Strings live in `res/values` (English) and `res/values-in` (Indonesian).
