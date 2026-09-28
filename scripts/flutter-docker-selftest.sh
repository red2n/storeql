#!/usr/bin/env bash
# Proves scripts/redeploy.sh's fallback for a machine with no Flutter SDK: the SDK at CI's pinned
# version runs in the SDK image as the host user (the image's own root-owned SDK failed as that user
# — git's "dubious ownership", then a cache flutter could not write — and its :stable was a release
# behind the code and could not compile it), and what it writes is the host user's. With --build it
# also builds the real web bundle that way, which takes minutes; the first run clones the SDK.
#
#   scripts/flutter-docker-selftest.sh [--build]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/flutter-in-docker.sh
source "$ROOT/scripts/lib/flutter-in-docker.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
passed=0; failed=0
check() { if eval "$2" >/dev/null 2>&1; then echo "  ok    $1"; passed=$((passed + 1)); else echo "  FAIL  $1"; failed=$((failed + 1)); fi; }

mkdir -p "$WORK/app" "$WORK/pub"
out="$(flutter_in_docker "$WORK/app" "$WORK/pub" 'flutter --version' 2>&1)"; rc=$?
[ $rc -eq 0 ] || printf '%s\n' "$out" | tail -5 | sed 's/^/        /'
check "flutter runs in the container, at the version CI builds with ($FLUTTER_VERSION)" "[ $rc -eq 0 ] && echo '$out' | grep -q '^Flutter $FLUTTER_VERSION '"
check "...neither refused by git nor stopped at its cache" "! echo '$out' | grep -q 'dubious ownership' && ! echo '$out' | grep -q 'Permission denied'"
check "the SDK cache on the host belongs to the host user" "[ \"\$(stat -c %u '$FLUTTER_SDK_CACHE/flutter/bin/flutter')\" = \"$(id -u)\" ]"
flutter_in_docker "$WORK/app" "$WORK/pub" 'echo made > made-here' >/dev/null 2>&1
check "what it writes belongs to the host user" "[ \"\$(stat -c %u '$WORK/app/made-here')\" = \"$(id -u)\" ]"

if [ "${1:-}" = "--build" ]; then
  rm -rf "$ROOT/frontends/storeql-app/build/web"
  flutter_in_docker "$ROOT/frontends/storeql-app" "$ROOT/.cache/flutter-pub-cache" \
    'flutter pub get && flutter build web --release --no-web-resources-cdn --dart-define=STOREQL_API_BASE=http://localhost:8090/api' >"$WORK/build.log" 2>&1
  [ $? -eq 0 ] || tail -15 "$WORK/build.log" | sed 's/^/        /'
  check "the web bundle is built in the container" "test -s '$ROOT/frontends/storeql-app/build/web/main.dart.js'"
  check "...and belongs to the host user" "[ \"\$(stat -c %u '$ROOT/frontends/storeql-app/build/web/main.dart.js')\" = \"$(id -u)\" ]"
fi
echo "flutter-in-docker self-test: $passed passed, $failed failed"
[ $failed -eq 0 ]
