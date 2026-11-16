# Architecture

Pattern: **MVVM** with a repository layer, Kotlin coroutines/Flow, and a hand-written dependency container (`di/AppContainer`). A DI framework can be introduced later if the graph grows.

```
UI (Compose screen) → ViewModel (StateFlow<UiState>) → Repository (interface) → data source
```

## Packages (`com.nexorith.browser`)

| Package | Purpose | Stage |
|---|---|---|
| `di` | `AppContainer`, wiring of repositories | 1 |
| `data` | Models and repositories shared across features | 1 |
| `ui` / `ui.theme` / `ui.home` | Compose theme and screens | 1–2 |
| `browser` | WebView host, navigation, errors, permissions | 3 |
| `bookmarks`, `history` | Persistence and screens | 4 |
| `adblock` | Filter parser, rule matching, request interception | 5 |
| `incognito` | Isolated profile and session cleanup | 6 |
| `downloads` | Download manager and accelerator | 7–8 |
| `vpn` | `VpnService` client and real backend | 9 |
| `network` | Shared HTTP utilities | as needed |
| `security` | URL validation, TLS policy, secure storage | 3, 10 |
| `settings` | Preferences and screens | as needed |

Empty packages contain only a `.gitkeep` until their stage starts.

## Conventions

- One ViewModel per screen; UI state is an immutable data class.
- Features are not shown as working until they are. Unavailable features are labelled "Upcoming" / "Akan datang" in the UI, with no stage or progress text.
- No secrets, keystores or tokens in the repository. Release signing uses CI secrets (stage 12).
- Strings live in `res/values` (English) and `res/values-in` (Indonesian).
