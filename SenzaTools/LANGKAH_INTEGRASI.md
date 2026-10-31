# Patch SenzaTools — hapus AI, logo baru, tombol liquid glass

Salin isi folder `app/` ke proyekmu (timpa file yang sama). Patch ini BELUM dikompilasi
(tidak ada Android SDK di sini), jadi build dulu dan kirim error-nya kalau ada.

## Isi patch
- `res/mipmap-*` + `mipmap-anydpi-v26` + `values/ic_launcher_background.xml`  → logo baru (adaptive icon)
- `ui/components/LiquidGlass.kt`   → komponen `LiquidGlassButton` (kaca cair)
- `ui/screens/UtilitiesScreen.kt`  → fitur pengganti AI: "Toolkit"
- `ui/screens/ToolsScreen.kt`      → tombol "Kirim ke AI" diganti "Salin log", teks AI dibersihkan
- `di/ServiceLocator.kt`           → AiClient / AiSettingsStore / SecureStore dihapus
- `platform/AccessManager.kt`      → satu teks batasan dibersihkan

## Langkah manual (file-nya tidak ada di ZIP yang kamu kirim)
1. **Tombol**: ganti isi fungsi `GlassButton` lama (di `ui/components`) menjadi:
   ```kotlin
   @Composable
   fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                   primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true) =
       LiquidGlassButton(text, onClick, modifier, primary, danger, enabled)
   ```
   Semua tombol di app otomatis jadi liquid glass.
2. **Navigasi**: di file yang memanggil `AIScreen(...)` (MainActivity / AppNav), ganti dengan
   `UtilitiesScreen(s)`, ubah label tab "AI" → "Toolkit".
3. **Nama app**: di `res/values/strings.xml` ubah `app_name` menjadi `Senza Tools` (file ini tidak ada di ZIP). Cari juga sisa teks "AI" di strings.xml dan SettingsScreen.
4. **Manifest**: pastikan `android:icon="@mipmap/ic_launcher"` dan `android:roundIcon="@mipmap/ic_launcher_round"`.

## Hapus setelah langkah 2
- Folder `ai/` KECUALI `SmartDiagnostics` (diagnostik lokal, tidak butuh internet) — pindahkan kalau mau.
- `AIScreen.kt`, bagian pengaturan API key di SettingsScreen (cari `aiSettings`, `aiClient`, `secure`).
- Dependency OkHttp / security-crypto di `build.gradle` dan izin `INTERNET` di manifest jika tidak dipakai lagi.
