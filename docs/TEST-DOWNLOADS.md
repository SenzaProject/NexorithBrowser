# Download manager: tests and limits

## Automated (`./gradlew testDebugUnitTest`)

- `DownloadEngineTest` runs the engine against a real HTTP server on localhost (JDK `HttpServer`), not mocks: full download, resume with Range and If-Range, server that ignores Range, file changed on the server, connection dropped mid-transfer, 429 with Retry-After (honoured, or fails when it is longer than 2 minutes), 404 not retried, 5xx exponential backoff, 416 on a complete file, cookies dropped on a cross-host redirect, cleartext refused, and cancellation of a stalled connection.
- `DownloadCenterTest`: queue limit, pause/resume/cancel/retry/remove, failure and publish errors, private downloads never stored, server file name, restart behaviour.
- `SpeedMeterTest`, `FormattersTest`, `FileNamesTest`.

## Manual checks on a device

Speed shown in the app is computed only from bytes received and elapsed time. To compare with another browser, download the same file on the same network, one after the other, and compare the **average speed** shown on the finished item with the other browser's figure.

| # | Step | Expected |
|---|---|---|
| 1 | Open a site with a direct file link (for example a Pixeldrain file link) and tap it | "Download started"; an item appears under Menu → Downloads with bytes, speed (KB/s or MB/s) and time left |
| 2 | Pull down the notification shade during the download | Ongoing notification with progress and "Pause all". On Android 13+ allow notifications when asked |
| 3 | Tap Pause, wait, tap Resume | Download continues from the same byte (the number does not drop). If the server does not advertise resume, a note is shown and the download may restart |
| 4 | Switch Wi-Fi off for ~20 s during a download, then on | Download recovers by itself (retries with backoff) and finishes; or shows "connection lost" with Retry, which continues from the saved bytes |
| 5 | Start 4 downloads | 3 run, 1 shows "Waiting" until a slot frees |
| 6 | Cancel a download, then Retry | Cancel removes the partial file; Retry starts from zero |
| 7 | Finish a download, tap Open | The file opens in an app that supports it; it is in the Downloads folder |
| 8 | Delete file, then check the Downloads folder | The file is gone |
| 9 | Download from an incognito tab | Item shows the incognito icon; notification does not show the file name; after force-stopping the app the item is gone |
| 10 | Force-stop the app during a download, reopen | The item is listed as Paused and is not resumed automatically; Resume continues it |

## Known limits (stage 7)

- One connection per download. Segmented/multi-connection download, checksum verification and the connection-count setting belong to stage 8.
- Downloads are not resumed automatically after the app process is killed; they come back as Paused.
- The finished file is copied from app storage to the Downloads folder, so a download temporarily needs roughly twice its size in free space.
- Android 8-9: the file stays in the app's own Downloads folder and cannot be opened from the app (the path is shown).
- Android 15 limits data-sync foreground services to about 6 hours per day; this is not handled yet.
- Downloads from incognito tabs send no cookies, so links that need a login fail there.
- Only http(s) links are downloaded; `blob:` and `data:` links are refused with a message.
- Notification actions: "Pause all" only. Per-download controls are in the Downloads screen.
- Nothing here has been compiled or run on a device yet.
