#!/usr/bin/env bash
# Offline guarantee + permission snapshot (spec 07 §4, D-8).
# Run after :app:processReleaseManifest (assembleRelease or bundleRelease).
set -euo pipefail
cd "$(dirname "$0")/../.."

# APK builds are split per ABI (.../arm64-v8a/AndroidManifest.xml); bundle builds are not (.../AndroidManifest.xml).
dir=app/build/intermediates/merged_manifests/release/processReleaseManifest
manifest=$dir/arm64-v8a/AndroidManifest.xml
[[ -f $manifest ]] || manifest=$dir/AndroidManifest.xml
[[ -f $manifest ]] || { echo "Merged manifest not found under $dir (run assembleRelease or bundleRelease first)"; exit 2; }

# The app's own permissions are prefixed with its application ID; normalise them to APP.* so the
# snapshot doesn't depend on the ID.
# (the attribute usually sits on its own line, so take the file's first package="…").
package=$(grep -m1 -oE 'package="[^"]+"' "$manifest" | sed -E 's/package="([^"]+)"/\1/')
[[ -n $package ]] || { echo "No package attribute in $manifest"; exit 2; }
actual=$(grep -oE 'uses-permission(-sdk-23)?[^>]*android:name="[^"]+"' "$manifest" \
  | sed -E 's/.*android:name="([^"]+)"/\1/' \
  | awk -v p="$package." '{ if (index($0, p) == 1) $0 = "APP." substr($0, length(p) + 1); print }' \
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
