#!/usr/bin/env bash
# arm64 download-size budget (spec 07 §4: arm64 APK <= 45 MB).
# Release builds are split per ABI; this measures the arm64-v8a APK's compressed payload.
set -euo pipefail
cd "$(dirname "$0")/../.."
apk=${1:-app/build/outputs/apk/release/app-arm64-v8a-release.apk}
budget_mb=${APK_BUDGET_MB:-45}
[[ -f $apk ]] || { echo "APK not found: $apk"; exit 2; }

bytes=$(unzip -v "$apk" | awk '$NF ~ /\// || NF >= 8 { if ($NF !~ /^lib\/armeabi-v7a\// && $3 ~ /^[0-9]+$/) s += $3 } END { print s }')
mb=$(awk -v b="$bytes" 'BEGIN { printf "%.1f", b / 1048576 }')
echo "arm64 payload: ${mb} MB (budget ${budget_mb} MB)"
awk -v m="$mb" -v l="$budget_mb" 'BEGIN { exit !(m <= l) }' || { echo "FAIL: over budget"; exit 1; }
echo "OK"
