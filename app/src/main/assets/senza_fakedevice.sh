#!/system/bin/sh
# Senza Tools - Fake Device (dibuat otomatis oleh aplikasi; jangan diedit manual)
D=/data/adb/senza_fakedevice
M=/data/adb/modules/senza_fakedevice
MODE=#MODE#
PERSIST=#PERSIST#
DEVICE_ID='#DEVICE_ID#'
SOC_ID='#SOC_ID#'

RP=""
for c in /data/adb/magisk/resetprop /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop /system/bin/resetprop resetprop; do
  case "$c" in
    /*) if [ -x "$c" ]; then RP="$c"; fi ;;
    *) if command -v "$c" >/dev/null 2>&1; then RP="$c"; fi ;;
  esac
  if [ -n "$RP" ]; then break; fi
done
if [ -z "$RP" ] && command -v magisk >/dev/null 2>&1; then RP="magisk resetprop"; fi

if [ "$MODE" = status ]; then
  if [ -f "$D/active.conf" ]; then cat "$D/active.conf"; fi
  if [ -f "$M/system.prop" ] && [ ! -f "$M/disable" ]; then echo "boot=1"; fi
  echo "rp=$RP"
  exit 0
fi

if [ -z "$RP" ]; then
  echo "GAGAL: resetprop tidak ditemukan (butuh Magisk, KernelSU, atau APatch)"
  exit 3
fi
mkdir -p "$D" || { echo "GAGAL: tidak bisa membuat $D"; exit 4; }
touch "$D/orig.conf"

# Simpan nilai asli sebelum diubah (sekali per properti), lalu timpa dan periksa hasilnya.
setp() {
  if ! grep -q "^$1=" "$D/orig.conf"; then echo "$1=$(getprop "$1")" >> "$D/orig.conf"; fi
  $RP -n "$1" "$2" >/dev/null 2>&1
  if [ "$(getprop "$1")" = "$2" ]; then echo "OK $1"; else echo "GAGAL $1"; fi
}

# Kembalikan satu properti ke nilai aslinya (kosong = hapus properti).
restorep() {
  line=$(grep "^$1=" "$D/orig.conf" | head -n 1)
  if [ -z "$line" ]; then return 0; fi
  v=${line#*=}
  if [ -n "$v" ]; then $RP -n "$1" "$v" >/dev/null 2>&1; else $RP --delete "$1" >/dev/null 2>&1; fi
  grep -v "^$1=" "$D/orig.conf" > "$D/orig.tmp"
  cat "$D/orig.tmp" > "$D/orig.conf"
  rm -f "$D/orig.tmp"
  echo "PULIH $1"
}

#PROPS#

if [ "$MODE" = apply ]; then
  printf 'device=%s\nsoc=%s\n' "$DEVICE_ID" "$SOC_ID" > "$D/active.conf"
  if [ "$PERSIST" = 1 ]; then
    mkdir -p "$M" || { echo "GAGAL: tidak bisa membuat module boot"; exit 5; }
    cat > "$M/module.prop" <<'SENZA_MODPROP'
id=senza_fakedevice
name=Senza Fake Device
version=1.0
versionCode=1
author=SenzaProject
description=Menyamarkan model perangkat dan SoC agar game membuka grafis lebih tinggi (Senza Tools)
SENZA_MODPROP
    cat > "$M/system.prop" <<'SENZA_VALUES'
#VALUES#
SENZA_VALUES
    cat > "$M/post-fs-data.sh" <<'SENZA_PFS'
#!/system/bin/sh
MODDIR=${0%/*}
RP=""
for c in /data/adb/magisk/resetprop /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop /system/bin/resetprop resetprop; do
  case "$c" in
    /*) if [ -x "$c" ]; then RP="$c"; fi ;;
    *) if command -v "$c" >/dev/null 2>&1; then RP="$c"; fi ;;
  esac
  if [ -n "$RP" ]; then break; fi
done
if [ -z "$RP" ] && command -v magisk >/dev/null 2>&1; then RP="magisk resetprop"; fi
if [ -z "$RP" ]; then exit 0; fi
while IFS='=' read -r k v; do
  if [ -n "$k" ]; then $RP -n "$k" "$v"; fi
done < "$MODDIR/system.prop"
exit 0
SENZA_PFS
    chmod 755 "$M/post-fs-data.sh"
    rm -f "$M/disable" "$M/remove"
    if [ -f "$M/system.prop" ] && [ -f "$M/module.prop" ]; then echo "OK module boot"; else echo "GAGAL module boot"; fi
  else
    rm -rf "$M"
  fi
else
  rm -f "$D/active.conf" "$D/orig.conf"
  rm -rf "$M"
  echo "OK dipulihkan"
fi
exit 0
