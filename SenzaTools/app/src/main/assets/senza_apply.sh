#!/system/bin/sh
# Senza Tools: pasang ulang hasil edit file (bind-mount) dan properti (resetprop).
# Aman dijalankan berulang. Dipakai langsung oleh aplikasi saat "Terapkan" dan otomatis tiap boot lewat:
#   - /data/adb/post-fs-data.d dan /data/adb/service.d (Magisk, KernelSU, APatch), dan/atau
#   - penerima BOOT_COMPLETED milik aplikasi (root apa pun, tanpa module).
#
# Pemakaian: sh senza_apply.sh               -> pasang semua bind + semua properti
#            sh senza_apply.sh --props FILE  -> hanya properti dari satu daftar (key=value per baris)

D="${0%/*}"

RP=""
for c in resetprop /data/adb/magisk/resetprop /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop; do
  if command -v "$c" >/dev/null 2>&1; then RP="$c"; break; fi
done

apply_props() {
  [ -f "$1" ] || return 0
  while IFS='=' read -r k v; do
    [ -n "$k" ] || continue
    case "$k" in \#*) continue ;; esac
    if [ -n "$RP" ]; then
      "$RP" -n "$k" "$v" >/dev/null 2>&1
    else
      # Tanpa resetprop hanya properti non-ro yang bisa diubah.
      case "$k" in ro.*|persist.*) ;; *) setprop "$k" "$v" >/dev/null 2>&1 ;; esac
    fi
  done < "$1"
}

if [ "$1" = "--props" ]; then
  apply_props "$2"
  exit 0
fi

# Mount harus terlihat oleh semua proses (namespace init), bukan hanya shell root ini.
NS=""
if command -v nsenter >/dev/null 2>&1 && nsenter --mount=/proc/1/ns/mnt -- true >/dev/null 2>&1; then
  NS="nsenter --mount=/proc/1/ns/mnt --"
fi

if [ -f "$D/binds.list" ]; then
  while IFS='|' read -r src dst; do
    [ -n "$src" ] && [ -f "$src" ] && [ -f "$dst" ] || continue
    # Isi sama berarti bind sudah terpasang (atau tidak ada yang perlu diganti).
    $NS cmp -s "$src" "$dst" && continue
    chcon --reference="$dst" "$src" >/dev/null 2>&1
    $NS mount --bind "$src" "$dst" >/dev/null 2>&1
  done < "$D/binds.list"
fi

for f in "$D"/props.d/*.list; do
  apply_props "$f"
done
exit 0
