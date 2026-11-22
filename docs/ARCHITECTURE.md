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
| `incognito` | `PrivateSession` (profile lifetime), `PrivateProfiles` (storage interface), `WebKitPrivateProfiles` (AndroidX WebKit multi-profile) | 6 |
| `downloads` | `DownloadCenter` (queue and state), `AcceleratedDownloader` (probe, chunks over several connections, checksum), `DownloadEngine` (single connection, Range, retry), `SegmentMap`, `ConcurrencyController`, `DownloadStorage` (part files, MediaStore), `DownloadService` and notifications | 7-8 |
| `vpn` | `VpnService` client and real backend | 9 |
| `network` | Shared HTTP utilities | as needed |
| `security` | URL validation, TLS policy, secure storage | 3, 10 |
| `settings` | Preferences and screens | as needed |

Empty packages contain only a `.gitkeep` until their stage starts.

## Browser flow

`BrowserViewModel` holds tab state (`TabState`) and never touches a WebView. The UI asks it to load a URL, which sets a one-shot `LoadRequest` on the tab. `BrowserScreen` performs the load on the tab's WebView (owned by `WebViewRegistry`) and tells the ViewModel the request was consumed. WebView callbacks flow back through `WebEvents` into the ViewModel. This keeps tab logic unit-testable without Android.

Security defaults: HTTPS-first address resolution, `javascript:`/`file:`/`content:`/`data:` blocked, cleartext traffic disabled, mixed content blocked, file/content access off, SSL errors cancelled, third-party cookies off, WebView debugging only in debug builds.

Incognito: a private tab (`TabState.isPrivate`) gets a WebView bound to a private WebView profile before it loads anything. `PrivateSession` creates the profile when the first private WebView is created and deletes it (cookies, cache, site storage) when the last one is destroyed. Profiles that could not be deleted, or were left by a crash, are removed on the next start. Private tabs never write history. If the installed WebView has no multi-profile support, incognito is unavailable and the app never falls back to the normal profile.

Downloads: the WebView `DownloadListener` hands a `DownloadRequest` to `DownloadCenter`. Each download runs in its own coroutine through `DownloadEngine`, which writes to an app-private `<id>.part` file and resumes from that file's length with an HTTP Range request (206 and Content-Range are checked; a server that ignores the range makes the download restart instead of corrupting the file). Speed is bytes received over a 3 s sliding window of real time. When finished, the file is copied to the public Downloads folder with MediaStore. `DownloadService` is a foreground service (type dataSync) that only observes the center to show the notification; it stops itself when nothing is active. Records are in the `downloads` table; downloads from incognito tabs are kept in memory only.

Accelerator (stage 8): `DownloadCenter` runs `AcceleratedDownloader`, which first sends `Range: bytes=0-0`. A `206` with the total size proves range support; a `200` means the server ignores ranges, and the work goes to `DownloadEngine` (one connection) while the UI shows that multi-connection mode is not available. With range support and a file of at least 4 MB, the file is preallocated and split into chunks (`SegmentMap`, about 32 chunks of 1-8 MB). Up to `maxConnections` workers claim chunks and fetch them with their own Range request, writing at their offset; `ConcurrencyController` raises the number of active workers one step at a time while throughput keeps growing and halves it on HTTP 429/503. Retry-After is honoured. Responses are validated: anything other than 206 with the expected Content-Range stops the chunked mode and falls back to one connection. The chunk map is saved with the download (`segment_state` column), so resume continues in the middle of a chunk; it is discarded if the file on the server changed (ETag / Last-Modified differ) or the file on disk does not fit the map. If the server sends `Digest` or `Repr-Digest`, the finished file is hashed and compared.

Known limits: tabs (including incognito tabs) are not restored after process death; the activity handles configuration changes itself so WebViews survive rotation.

## Conventions

- One ViewModel per screen; UI state is an immutable data class.
- Features are not shown as working until they are. Unavailable features are labelled "Upcoming" / "Akan datang" in the UI, with no stage or progress text.
- No secrets, keystores or tokens in the repository. Release signing uses CI secrets (stage 12).
- Strings live in `res/values` (English) and `res/values-in` (Indonesian).
