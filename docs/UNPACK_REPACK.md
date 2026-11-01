# Unpack & Repack

Fitur di **Toolkit → Unpack & Repack** untuk membongkar dan merakit ulang image ROM langsung di HP. Butuh **root**.

Folder kerja: `/sdcard/Senza/UnpackRepack` (proyek diawali `DNA_`). Hasil sementara: `/data/senza_unpackrepack`.

## Menu
| Tab | Isi |
|---|---|
| Proyek | Buat, pilih, hapus proyek; Unzip ROM |
| Unpack | payload.bin, br, dat, img (ext4/erofs/f2fs, auto-deteksi), super |
| Repack | Satu partisi (ext4/erofs/f2fs, rw/ro, ukuran, sparse/raw, img/dat/br, level br/erofs, mode erofs, padatkan ext4, hapus avb), super.img (a_only/ab/virtual_ab) |
| Konversi | img↔simg, img→dat/br, zst↔img, gabung sparse bersegmen |
| Partisi | Ekstrak partisi dari perangkat, flash img ke partisi |
| Skrip | Eksekutor perintah di folder proyek dengan semua tool di PATH |

## Tool bawaan (`app/src/main/assets/unpackrepack/tools`)
`dna`, `brotli`, `busybox`, `e2fsdroid`, `extract.erofs`, `extract.f2fs`, `img2simg`, `simg2img`, `lpmake`, `magiskboot`, `mke2fs`, `mkfs.erofs`, `mkfs.f2fs`, `resize2fs`, `sload_f2fs`, `zstd`. Semuanya biner arm64 dan disalin ke penyimpanan internal aplikasi saat pertama dipakai. Ubah `TOOLS_REV` di `UnpackEngine.kt` bila isi folder ini diganti.

## Pengaman
- Flash partisi memakai dialog konfirmasi, dan partisi `system*`, `vendor*`, `product*`, `odm*`, `super`, `userdata`, `metadata` diblokir.
- Eksekutor skrip melewati `CommandGuard` yang sama dengan tab Shell.
- Nama proyek dibatasi `[A-Za-z0-9_.-]`.

## Belum dibawa
Menu Patch/Jar/ROM yang disebut README DNA-Android (patch vbmeta, debloat, dll.) dan sistem plugin `.zip2` tidak ada di sumber yang dikirim (`dna.xml` hanya berisi Unpack, Repack, Konversi), jadi tidak ikut disalin.

## Kredit
Berasal dari DNA-Android (modded by @SoutaEver), yang memakai magiskboot, kr-scripts, TIK toolbox, sdat2img/img2sdat, erofs-extract, DNA (gitee/sharpeter), dan fspatch. Tiap tool tetap mengikuti lisensi aslinya.
