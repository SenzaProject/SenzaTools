# Patch: Logcat & SELinux akurat dan konsisten

## Penyebab hasil berbeda-beda (kode lama)
1. **Logcat `-t N`**: jendela N baris terakhir bergeser tiap kali log baru masuk, dan `-t` + filter level dihitung di sisi logcat (jumlah baris tidak pasti).
2. **Keluaran dipotong di 300.000 karakter dari DEPAN**, jadi baris paling baru hilang (di uji: hanya 1561 dari 2000 baris sampai ke layar).
3. **stderr dicampur ke log**: pesan error (mis. `dmesg: Operation not permitted`) tampil seolah-olah isi log dengan status "berhasil".
4. **Penanda buffer** (`--------- beginning of ...`) ikut terhitung sebagai baris log.
5. **Scan denial**: `dmesg | tail -n 500` lalu *cadangan* `logcat -t 2000` hanya bila dmesg kosong. Denial yang sudah keluar dari jendela hilang, sumber hasil bisa berpindah, hitungan ganda antar sumber, dan urutan hasil seri bergantung urutan baris.
6. **Mode SELinux** dibaca dari sumber berbeda tergantung kapan root selesai diverifikasi, dan layar lain menganggap nilai apa pun selain `1` sebagai Permissive.
7. Tombol **Salin** hanya menyalin 300 baris yang tampil, lalu dipotong di tengah baris.

## Perbaikan
- `LogEngine` / `LogParse`: ambil buffer UTUH (`logcat -d`), buang penanda, filter level lokal, lalu N baris terakhir. Hasil gagal/terputus tidak pernah ditampilkan sebagai log. Setiap hasil punya **sidik jari** (SHA-256 12 hex): isi sama = sidik jari sama.
- `Sys.stream`: baca stdout baris demi baris, stderr terpisah, tanpa pemotongan kepala, dan melaporkan `complete`.
- `SelinuxEngine`: mode dibaca dari semua sumber (root > sysfs > getenforce) dengan urutan tetap dan konflik dilaporkan; scan denial selalu membaca dmesg DAN logcat penuh, digabung tanpa hitung ganda lewat id audit.
- `SelinuxAnalyzer`: perms dinormalkan, `comm`/`permissive` ditentukan dari isi (bukan urutan), urutan hasil = jumlah lalu kunci.
- Satu parser mode SELinux (`SelinuxMode`) dipakai di semua layar.

## Yang tetap berubah (bukan bug)
Log adalah aliran hidup. Bila sistem menulis baris baru, "N baris terakhir" ikut berubah. Pakai sidik jari untuk memastikan: isi sama = hasil sama. Hasil scan denial TIDAK ikut berubah oleh baris baru yang bukan avc.

## Menguji
- Unit test (jalan di CI): `LogToolsTest`, `SelinuxTest`.
- Uji proses nyata 1000x dengan Android palsu: `./verify/run_verify.sh` (butuh kotlinc, java, python3).
