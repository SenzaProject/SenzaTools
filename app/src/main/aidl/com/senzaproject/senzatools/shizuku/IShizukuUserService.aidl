// Interface AIDL yang dijalankan Shizuku di proses dengan UID shell (ADB) atau root, tergantung
// mode Shizuku yang dipakai pengguna. Ini definisi standar yang sama dipakai aplikasi lain yang
// terintegrasi Shizuku (lihat demo resmi rikka/Shizuku-API di GitHub untuk pola yang sama).
package com.senzaproject.senzatools.shizuku;

interface IShizukuUserService {
    /** Jalankan satu perintah shell dan kembalikan stdout+stderr gabungan beserta exit code. */
    String execShell(String command, int[] exitCodeOut) = 1;

    /** UID proses ini berjalan (2000 = shell/ADB, 0 = root) — untuk ditampilkan ke pengguna. */
    int getUid() = 2;

    /** Matikan service ini (dipanggil saat tidak dipakai lagi untuk melepas proses Shizuku). */
    void destroy() = 16777114; // nomor transaksi khusus yang dikenali Shizuku untuk shutdown bersih
}
