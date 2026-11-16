# Roadmap Pengembangan Nexorith Browser

**Project:** Nexorith Browser  
**Developer:** NexorithLab  
**Package ID:** `com.nexorith.browser`  
**GitHub:** [SenzaProject/NexorithBrowser](https://github.com/SenzaProject/NexorithBrowser)  
**Browser Engine:** Android WebView  
**UI Framework:** Kotlin + Jetpack Compose  
**VPN:** Backend VPN sungguhan  
**Target:** Android APK v1.0.0

---

## Tujuan

Mengembangkan Nexorith Browser menjadi browser Android yang stabil, aman, dan benar-benar berfungsi. Semua fitur harus diuji; tampilan tombol atau simulasi bukan bukti bahwa fitur sudah bekerja.

## 12 Tahap Pengerjaan

| No. | Tahap | Ruang lingkup | Kriteria selesai |
|---:|---|---|---|
| 1 | Setup project dan struktur aplikasi | Konfigurasi Gradle, package, arsitektur MVVM, dependency, repository, dan GitHub Actions awal | Project dapat dibangun di CI dan struktur kode terdokumentasi |
| 2 | Desain UI/UX dan branding | Logo, warna, typography, homepage, navigasi, komponen, dan tampilan responsif | UI konsisten, dapat digunakan, dan sesuai identitas Nexorith |
| 3 | Browser WebView | Address/search bar, HTTPS, back, forward, refresh, error page, dan pengelolaan permission | Dapat membuka dan menavigasi website HTTPS dengan aman |
| 4 | Multi-tab, bookmark, dan history | Membuat, berpindah, dan menutup tab; menyimpan bookmark dan riwayat | Data navigasi bekerja dan dapat dikelola pengguna |
| 5 | AdBlock | Filter list, pemblokiran request, allowlist, pembaruan filter, dan statistik | Pengujian menunjukkan request iklan yang cocok dengan aturan benar-benar diblokir |
| 6 | Incognito | Profil/storage privat terpisah, cookie dan cache privat, penutupan sesi dan penghapusan data | Data sesi privat tidak tercampur dengan profil normal dan dibersihkan sesuai kebijakan |
| 7 | Download Manager | Daftar unduhan, progress byte aktual, speed, ETA, pause, resume, cancel, retry, dan notifikasi | Unduhan dapat dikelola dan pulih dari gangguan jaringan yang diuji |
| 8 | Advanced Download Accelerator | HTTP Range, validasi Content-Range, segmented download bila didukung server, adaptive concurrency, dan checksum bila tersedia | Pengujian membuktikan kecepatan dari byte aktual; resume/segmen tervalidasi |
| 9 | VPN backend | Integrasi Android VpnService dengan protokol dan server nyata, autentikasi, status tunnel, DNS, dan lifecycle | Tunnel benar-benar tersambung dan lulus pengujian koneksi serta kebocoran DNS/IP |
| 10 | Security dan optimasi | Validasi URL, TLS, permission, penyimpanan aman, privasi, performa, baterai, dan memory | Pemeriksaan keamanan dan performa selesai tanpa masalah kritis yang diketahui |
| 11 | Testing, bug fixing, dan benchmark | Unit test, instrumented test, pengujian perangkat, pengujian unduhan, VPN, AdBlock, dan regresi | Laporan pengujian tersedia dan bug kritis ditangani |
| 12 | Build APK dan release | CI final, build debug/release, signing aman, versioning, changelog, dan dokumentasi rilis | APK berhasil dibangun, dapat diinstal, dan artefak rilis tersedia |

---

## Catatan teknis penting

### Download Accelerator

Nexorith Browser dapat mengoptimalkan download menggunakan HTTP Range, koneksi paralel, resume, dan pengukuran throughput aktual jika server mendukungnya.

Aplikasi **tidak dapat menjamin penghapusan batas kecepatan** yang diberlakukan server, akun, alamat IP, kuota, autentikasi, atau kebijakan layanan. Hormati HTTP `429`, `Retry-After`, dan aturan layanan. Jangan pernah menampilkan kecepatan simulasi.

### VPN

Fitur VPN membutuhkan infrastruktur server yang benar-benar tersedia, konfigurasi protokol, autentikasi, serta biaya operasional bila ada. Jangan menampilkan status **Connected** sebelum tunnel nyata terbentuk dan diverifikasi.

### Incognito

Mode penyamaran harus menggunakan isolasi data yang sungguhan. Incognito tidak membuat pengguna anonim dari website, ISP, administrator jaringan, atau penyedia VPN.

### AdBlock

Pemblokiran iklan bergantung pada filter list dan kemampuan interception WebView. Jangan mengklaim memblokir seluruh jenis iklan atau tracking tanpa pengujian.

---

## Checklist rilis v1.0.0

- [ ] Project berhasil dibangun melalui GitHub Actions.
- [ ] APK dapat dipasang dan dibuka pada perangkat Android target.
- [ ] Browsing HTTPS, navigasi, dan multi-tab berfungsi.
- [ ] Bookmark dan history berfungsi.
- [ ] AdBlock diuji dengan domain dan filter list pengujian.
- [ ] Incognito benar-benar mengisolasi dan membersihkan data sesi.
- [ ] Download manager menampilkan byte dan kecepatan aktual.
- [ ] Pause, resume, retry, dan penanganan gangguan download diuji.
- [ ] Range/segmented download hanya digunakan saat server mendukung dan respons tervalidasi.
- [ ] VPN terhubung ke server nyata dan lulus pemeriksaan DNS/IP.
- [ ] Tidak ada credential atau private key di repository.
- [ ] Pengujian keamanan, performa, dan regresi selesai.
- [ ] README, changelog, dan laporan pengujian tersedia.

## Aturan pengerjaan

1. Kerjakan tahap secara berurutan.
2. Uji setiap tahap sebelum berpindah ke tahap berikutnya.
3. Perbaiki regresi yang muncul akibat perubahan baru.
4. Tandai fitur sebagai `IMPLEMENTED`, `PARTIAL`, atau `NOT IMPLEMENTED` secara jujur.
5. Jangan menyatakan tahap selesai hanya karena UI sudah dibuat atau APK berhasil di-build.
6. Catat dependensi eksternal, konfigurasi server, dan persyaratan yang masih dibutuhkan.

**Target akhir:** Nexorith Browser v1.0.0 — *Browse Freely. Without Limits.*
