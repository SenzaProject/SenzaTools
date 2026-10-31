# Arsitektur

```
presentation  ui/screens, ui/components, ui/theme, ViewModels (Analyzer, Chat)
domain        domain/model (model + enum), validation rules
data          DeviceRepository, OperationLog, UiPrefs, HistoryStore
platform      AccessManager, root/SafeActions (PathGuard, CommandGuard), normal/adb/root
analyzer      system (prop, rc, xml, selinux, compat), apk, rom
ai            AiClient, PromptManager, ResponseValidator, SmartDiagnostics, SecureStore
monitor       PerformanceMonitor
di            ServiceLocator (manual DI)
```

## Keputusan desain dan penyimpangan dari spesifikasi
| Spesifikasi | Implementasi | Alasan |
|---|---|---|
| Hilt | `ServiceLocator` manual | Menghindari kapt/ksp agar build awal stabil; antarmuka sudah dipisah sehingga migrasi ke Hilt mudah. |
| Room | File teks/CSV (log, riwayat) | Data kecil dan sederhana; Room akan dipakai bila riwayat jangka panjang dibutuhkan. |
| Modul `core/` `feature/` | Satu modul `app` dengan paket terpisah | Menurunkan risiko konfigurasi Gradle multi-modul sebelum build pertama terverifikasi. |
| WorkManager | Tidak dipakai | Monitor sengaja hanya berjalan saat UI aktif (hemat baterai, tanpa layanan latar belakang). |
| Chart library | Canvas Compose sendiri | Tanpa dependensi tambahan. |

## Aliran data
UI → (ViewModel untuk analisis berat/chat, langsung ke repository untuk data ringan) → repository/analyzer → Android API / file pilihan pengguna (SAF) / `AccessManager`.
Semua pekerjaan berat berjalan di `Dispatchers.IO`; hasil analisis disimpan di ViewModel sehingga bertahan saat pindah tab.

## Akses
`AccessManager` memegang status Normal/Root. Root tidak pernah diminta otomatis; `verifyRoot()` hanya dipanggil setelah dialog persetujuan. Eksekusi hanya lewat `SafeAction` terdefinisi atau perintah yang diketik pengguna dan melewati `CommandGuard`.
