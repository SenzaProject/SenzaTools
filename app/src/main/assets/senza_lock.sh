#!/system/bin/sh
# Dibuat oleh Senza Tools - penerap dan penjaga frekuensi CPU/GPU.
# Mode: once (terapkan sekali) | loop (terapkan lalu jaga terus) | stop | status
#BOOTWAIT#
PIDF=/data/local/tmp/senza_lock.pid
MODE="${1:-#DEFMODE#}"

# Apakah PID $1 benar-benar proses penjaga Senza (bukan proses lain yang kebetulan memakai PID sama).
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

# rng <dir policy> <min> <max> <terendah> <governor|->
# Menulis hanya bila nilai sekarang berbeda, lalu memeriksa hasilnya.
rng() {
  d="$1"; mn="$2"; mx="$3"; lo="$4"; gv="$5"
  [ -d "$d" ] || return 0
  if [ -n "$gv" ] && [ "$gv" != "-" ]; then
    cg=
    read -r cg < "$d/scaling_governor"
    [ "$cg" = "$gv" ] || echo "$gv" > "$d/scaling_governor" 2>/dev/null
  fi
  cmn=; cmx=
  read -r cmn < "$d/scaling_min_freq"
  read -r cmx < "$d/scaling_max_freq"
  if [ "$cmn" != "$mn" ] || [ "$cmx" != "$mx" ]; then
    echo "$lo" > "$d/scaling_min_freq" 2>/dev/null
    echo "$mx" > "$d/scaling_max_freq" 2>/dev/null
    echo "$mn" > "$d/scaling_min_freq" 2>/dev/null
    cmn=; cmx=
    read -r cmn < "$d/scaling_min_freq"
    read -r cmx < "$d/scaling_max_freq"
    [ "$cmx" = "$mx" ] || echo "GAGAL: $d/scaling_max_freq (kini $cmx)"
    [ "$cmn" = "$mn" ] || echo "GAGAL: $d/scaling_min_freq (kini $cmn)"
  fi
  return 0
}

# drg <dir devfreq GPU> <min> <max> <terendah>
drg() {
  d="$1"; mn="$2"; mx="$3"; lo="$4"
  [ -d "$d" ] || return 0
  cmn=; cmx=
  read -r cmn < "$d/min_freq"
  read -r cmx < "$d/max_freq"
  if [ "$cmn" != "$mn" ] || [ "$cmx" != "$mx" ]; then
    echo "$lo" > "$d/min_freq" 2>/dev/null
    echo "$mx" > "$d/max_freq" 2>/dev/null
    echo "$mn" > "$d/min_freq" 2>/dev/null
    cmn=; cmx=
    read -r cmn < "$d/min_freq"
    read -r cmx < "$d/max_freq"
    [ "$cmx" = "$mx" ] || echo "GAGAL: $d/max_freq (kini $cmx)"
    [ "$cmn" = "$mn" ] || echo "GAGAL: $d/min_freq (kini $cmn)"
  fi
  return 0
}

# MediaTek PPM: batas user per cluster supaya limit dari PPM tidak menimpa nilai cpufreq. -1 = tanpa batas.
# ppm <index cluster> <min|-1> <max|-1>
ppm() {
  [ -e /proc/ppm/policy/hard_userlimit_max_cpu_freq ] || return 0
  echo "$1 -1" > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null
  echo "$1 $3" > /proc/ppm/policy/hard_userlimit_max_cpu_freq 2>/dev/null
  echo "$1 $2" > /proc/ppm/policy/hard_userlimit_min_cpu_freq 2>/dev/null
  return 0
}

# MediaTek GED: batas bawah (boost) dan batas atas (upbound) GPU dalam kHz. 0 = lepas.
# ged <boost_khz> <upbound_khz>
ged() {
  for p in /sys/module/ged/parameters/gpu_cust_boost_freq /sys/kernel/ged/hal/custom_boost_gpu_freq; do
    [ -e "$p" ] && echo "$1" > "$p" 2>/dev/null
  done
  for p in /sys/module/ged/parameters/gpu_cust_upbound_freq /sys/kernel/ged/hal/custom_upbound_gpu_freq; do
    [ -e "$p" ] && echo "$2" > "$p" 2>/dev/null
  done
  return 0
}

# Kunci GPU MediaTek lewat /proc/gpufreq (kHz). 0 = lepas kunci.
gmtk() {
  [ -e /proc/gpufreq/gpufreq_opp_freq ] && echo "$1" > /proc/gpufreq/gpufreq_opp_freq 2>/dev/null
  ged "$1" "$1"
  return 0
}

# thermal <1|0>: 1 = abaikan limit thermal GPU MediaTek (berisiko panas), 0 = normal.
thermal() {
  [ -e /proc/gpufreq/gpufreq_limited_thermal_ignore ] && echo "$1" > /proc/gpufreq/gpufreq_limited_thermal_ignore 2>/dev/null
  return 0
}

apply() {
:
#TARGETS#
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
  sleep 3
done
