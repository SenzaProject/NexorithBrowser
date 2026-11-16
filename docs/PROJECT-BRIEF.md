# Nexorith Browser — Project Brief

## Tujuan
Browser Android dengan fokus pada privasi, pengalaman browsing bersih, VPN backend sungguhan,
dan download manager yang mengoptimalkan throughput secara sah.

## Identitas
- Product: Nexorith Browser
- Developer: NexorithLab
- Package: com.nexorith.browser
- Repo: SenzaProject/NexorithBrowser
- Engine: Android WebView
- UI: Jetpack Compose + Kotlin
- Build: Gradle / GitHub Actions
- Tagline: Browse Freely. Without Limits.

## Fitur sasaran
- Web browser: address/search, back/forward/reload, multi-tab, bookmark, history.
- AdBlock: filter list, domain rules, allowlist, statistics.
- VPN: Android VpnService + backend/protocol nyata; server diperlukan.
- Incognito: profil/storage terpisah dan penghapusan data sesi.
- Download manager: progress dan speed aktual, resume, pause, retry, Range/segmented jika server mendukung.
- Settings: privacy, security, download, search, language.

## Prinsip penting
- Tidak ada fake VPN connected state.
- Tidak ada speed download palsu.
- Tidak ada klaim menghilangkan server/account/IP rate limits.
- Hormati HTTP 429, Retry-After, autentikasi, dan ketentuan layanan.
- Tidak ada bypass TLS/SSL errors.
- Jangan commit password, token, private keys, atau signing keystore.

## Branding
Lihat `../DESIGN-REFERENCES/` untuk poster konsep UI dan identitas visual.
Logo SVG awal ada di `../NexorithBrowser/assets/nexorith-logo.svg`.
