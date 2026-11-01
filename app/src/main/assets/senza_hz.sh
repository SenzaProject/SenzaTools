#!/system/bin/sh
# Dibuat oleh Senza Tools - penerap dan penjaga refresh rate layar.
# Mode: once (terapkan sekali) | loop (terapkan lalu jaga terus) | stop | status
#BOOTWAIT#
HZ=#HZ#
PIDF=/data/local/tmp/senza_hz.pid
MODE="${1:-#DEFMODE#}"

# Apakah PID $1 benar-benar proses penjaga ini (bukan proses lain yang kebetulan memakai PID sama).
isme() {
  case "$(tr '\0' ' ' < "/proc/$1/cmdline" 2>/dev/null)" in
    *senza*) return 0 ;;
  esac
  return 1
}

stopold() {
  if [ -f "$PIDF" ]; then
    op=
    read -r op < "$PIDF"
    if [ -n "$op" ] && [ "$op" != "$$" ] && isme "$op"; then
      kill "$op" 2>/dev/null
    fi
    rm -f "$PIDF"
  fi
}

# setkey <kunci> <nilai>: tulis ke Settings.System hanya bila berbeda, lalu periksa hasilnya.
setkey() {
  cur=$(settings get system "$1" 2>/dev/null)
  [ "$cur" = "$2" ] && return 0
  settings put system "$1" "$2" 2>/dev/null
  cur=$(settings get system "$1" 2>/dev/null)
  [ "$cur" = "$2" ] || echo "GAGAL: $1 (kini $cur)"
  return 0
}

apply() {
  # AOSP (DisplayModeDirector): min = peak = HZ mengunci rentang yang diminta sistem.
  setkey min_refresh_rate "$HZ.0"
  setkey peak_refresh_rate "$HZ.0"
  # Kunci vendor (mis. Xiaomi) hanya ditulis bila kuncinya sudah ada di perangkat ini.
  uc=$(settings get system user_refresh_rate 2>/dev/null)
  case "$uc" in
    ""|null|"$HZ"|"$HZ.0") ;;
    *) settings put system user_refresh_rate "$HZ" 2>/dev/null ;;
  esac
}

case "$MODE" in
  once)
    apply
    exit 0
    ;;
  stop)
    stopold
    exit 0
    ;;
  status)
    if [ -f "$PIDF" ]; then
      op=
      read -r op < "$PIDF"
      if [ -n "$op" ] && isme "$op"; then echo "ON"; fi
    fi
    exit 0
    ;;
esac

stopold
echo "$$" > "$PIDF"
while true; do
  apply
  sleep 10
done
