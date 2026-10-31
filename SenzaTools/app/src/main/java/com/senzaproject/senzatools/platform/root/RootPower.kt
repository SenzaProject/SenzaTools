package com.senzaproject.senzatools.platform.root

/**
 * Perintah restart yang tidak bergantung pada satu cara saja.
 *
 * Sebelumnya restart memakai `svc power reboot || reboot`. Di sebagian ROM `svc power reboot` keluar dengan
 * status 0 padahal layanan daya menolak permintaannya, jadi `|| reboot` tidak pernah dijalankan dan perangkat
 * diam saja. Sekarang beberapa cara dicoba berurutan (tanpa bergantung pada status keluar), dan seluruh rantai
 * dijalankan terlepas dari proses `su` supaya tidak ikut mati ketika `su` dihentikan karena timeout.
 */
object RootPower {
    private val REASON = Regex("^[a-z]{1,16}$")

    /**
     * @param reason null untuk restart normal; atau "recovery" / "bootloader" / "fastboot".
     * Restart normal ditutup dengan SysRq (sync, remount read-only, reboot) sebagai upaya terakhir.
     */
    fun command(reason: String? = null): String {
        if (reason != null) require(REASON.matches(reason)) { "Alasan restart tidak valid" }
        val chain = if (reason == null) {
            "sync; " +
                "svc power reboot >/dev/null 2>&1; sleep 4; " +
                "/system/bin/reboot >/dev/null 2>&1; sleep 3; " +
                "setprop sys.powerctl reboot >/dev/null 2>&1; sleep 3; " +
                "echo 1 > /proc/sys/kernel/sysrq 2>/dev/null; " +
                "echo s > /proc/sysrq-trigger 2>/dev/null; sleep 1; " +
                "echo u > /proc/sysrq-trigger 2>/dev/null; sleep 1; " +
                "echo b > /proc/sysrq-trigger 2>/dev/null"
        } else {
            "sync; " +
                "svc power reboot $reason >/dev/null 2>&1; sleep 4; " +
                "/system/bin/reboot $reason >/dev/null 2>&1; sleep 3; " +
                "setprop sys.powerctl reboot,$reason >/dev/null 2>&1"
        }
        return "( nohup sh -c '$chain' >/dev/null 2>&1 </dev/null & )"
    }
}
