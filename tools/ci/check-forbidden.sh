#!/usr/bin/env bash
# Forbidden dependencies, transitive group allowlist and destructive-migration grep (spec 07 §4).
set -euo pipefail
cd "$(dirname "$0")/../.."
ci=tools/ci

deps=$(./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain -q \
  | grep -oE '[A-Za-z0-9._-]+:[A-Za-z0-9._-]+:[A-Za-z0-9._-]+' | cut -d: -f1,2 | sort -u)
fail=0

# 1. Forbidden artifacts, minus the reviewed exceptions (ML Kit's own bundled-model plumbing).
exceptions=$(grep -vE '^\s*(#|$)' $ci/allowed-exceptions.txt || true)
while IFS= read -r pattern; do
  hits=$(grep -E "^${pattern}" <<<"$deps" | grep -vxF -f <(echo "$exceptions") || true)
  if [[ -n $hits ]]; then echo "FAIL: forbidden dependency ($pattern):"; echo "$hits"; fail=1; fi
done < <(grep -vE '^\s*(#|$)' $ci/forbidden.txt)

# 2. Every Maven group must be on the reviewed allowlist.
new_groups=$(cut -d: -f1 <<<"$deps" | sort -u | grep -vxF -f <(grep -vE '^\s*(#|$)' $ci/allowed-groups.txt) || true)
if [[ -n $new_groups ]]; then echo "FAIL: new Maven group(s) need review:"; echo "$new_groups"; fail=1; fi

# 3. No destructive Room migrations, ever.
if grep -rn --include='*.kt' fallbackToDestructiveMigration domain data app; then
  echo "FAIL: fallbackToDestructiveMigration is not allowed."; fail=1
fi

[[ $fail -eq 0 ]] && echo "OK: dependencies and migrations pass."
exit $fail
