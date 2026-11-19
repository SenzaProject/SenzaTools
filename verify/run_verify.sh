#!/usr/bin/env bash
# Uji akurasi Logcat & SELinux dengan "Android palsu" (skrip su/logcat/dmesg/getenforce/setenforce) dan
# jawaban benar yang dihitung terpisah di Python. Memakai kode ASLI di app/src/main (bukan salinan).
# Butuh: kotlinc, java, python3.   Pemakaian:  ./verify/run_verify.sh [jumlah_ulang=1000]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; M="$HERE/../app/src/main/java/com/senzaproject/senzatools"
RUNS="${1:-1000}"; W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
python3 "$HERE/gen_fixture.py" "$W/dev" >/dev/null
kotlinc -nowarn -d "$W/out" "$HERE"/shim/android_stubs*.kt "$M/utils/Sys.kt" "$M/platform/root/SystemShell.kt" \
  "$M/platform/root/LogEngine.kt" "$M/platform/root/SelinuxEngine.kt" "$M/analyzer/system/SelinuxAnalyzer.kt" \
  "$HERE/old/OldAnalyzer.kt" "$HERE/Verify.kt"
STD="$(dirname "$(readlink -f "$(command -v kotlinc)")")/../lib/kotlin-stdlib.jar"
FAKE_DIR="$W/dev" PATH="$HERE/fakebin:$PATH" java -cp "$W/out:$STD" VerifyKt "$W/dev" "$RUNS"
