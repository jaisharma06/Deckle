#!/usr/bin/env bash
# Offline guarantee + permission snapshot (spec 07 §4, D-8).
# Run after :app:processReleaseManifest (or assembleRelease).
set -euo pipefail
cd "$(dirname "$0")/../.."

manifest=app/build/intermediates/merged_manifests/release/processReleaseManifest/arm64-v8a/AndroidManifest.xml
[[ -f $manifest ]] || { echo "Merged manifest not found: $manifest (run assembleRelease first)"; exit 2; }

actual=$(grep -oE 'uses-permission(-sdk-23)?[^>]*android:name="[^"]+"' "$manifest" \
  | sed -E 's/.*android:name="([^"]+)"/\1/' \
  | sed 's/^com\.avyaan\.deckle\./APP./' \
  | sort -u)

if grep -qE '^android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE)$' <<<"$actual"; then
  echo "FAIL: a network permission is present in the merged manifest:"
  grep -E 'INTERNET|NETWORK|WIFI' <<<"$actual"
  exit 1
fi

if ! diff -u tools/ci/expected-permissions.txt <(echo "$actual"); then
  echo "FAIL: merged permissions changed. Review the diff above; if intended, update tools/ci/expected-permissions.txt."
  exit 1
fi
echo "OK: no network permission; permissions match the snapshot."
