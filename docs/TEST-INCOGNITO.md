# Incognito test checklist

Automated: `PrivateSessionTest` (profile lifetime, retry, leftovers, unsupported WebView) and the incognito cases in `BrowserViewModelTest` (no history, close-all, tab selection).

The isolation itself depends on the device WebView, so it has to be checked on a real device. Use `https://httpbin.org/cookies/set?nexorith=normal` (sets a cookie, then shows the cookies the site received).

| # | Step | Expected |
|---|---|---|
| 1 | In a normal tab open `https://httpbin.org/cookies/set?nexorith=normal` | Page lists `nexorith: normal` |
| 2 | Menu → New incognito tab, open `https://httpbin.org/cookies` | Empty cookie list (normal cookie not visible) |
| 3 | In the incognito tab open `https://httpbin.org/cookies/set?nexorith=private` | Lists `nexorith: private` |
| 4 | Back to the normal tab, open `https://httpbin.org/cookies` | Only `nexorith: normal` |
| 5 | Tabs → Incognito → Close all incognito tabs, then open a new incognito tab and `https://httpbin.org/cookies` | Empty list (private cookie deleted) |
| 6 | Browse a page in an incognito tab, then open Menu → History and the Home page of a normal tab | Page is not in History or Recently visited |
| 7 | Open an incognito tab, force-stop the app, start it again, open an incognito tab and `https://httpbin.org/cookies` | Empty list (leftover profile purged) |
| 8 | Incognito Home screen | Says incognito does not make you anonymous |
| 9 | Device with an old WebView (no multi-profile support) | "Incognito unavailable" dialog; no incognito tab is created |

Not covered: screenshots are not blocked in incognito tabs, and the app does not hide incognito content in the recent-apps preview.
