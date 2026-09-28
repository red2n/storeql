#!/usr/bin/env bash
# The web bundle built with no Flutter SDK installed. The SDK is cloned once, at the version CI builds
# with (.github/workflows/ci.yml, flutter-version), into a cache beside the pub cache on the host, and
# run inside the SDK image as the host user — the image supplies the toolchain's OS, not its Flutter:
# no registry publishes an image at that version promptly (cirruslabs' :stable was a release behind
# the code and could not compile it), and an SDK the host user owns spares the two failures a
# root-owned one gave (git's "dubious ownership", then a cache flutter could not write). What it
# writes — build/web, .dart_tool, pubspec.lock, the caches — is the host user's from the start.
# Sourced by scripts/redeploy.sh; proved by scripts/flutter-docker-selftest.sh.
#
# The build writes the app's .dart_tool/package_config.json with the container's paths; a machine
# that also has Flutter installed runs `flutter pub get` once afterwards to point it back home.
#
#   flutter_in_docker <app dir> <pub cache dir> <shell command>

_FID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FLUTTER_VERSION="${FLUTTER_VERSION:-$(grep -m1 -oE "flutter-version: '[0-9.]+'" "$_FID_ROOT/.github/workflows/ci.yml" | grep -oE '[0-9.]+')}"
FLUTTER_IMAGE="${FLUTTER_IMAGE:-ghcr.io/cirruslabs/flutter:3.44.0}"
FLUTTER_SDK_CACHE="${FLUTTER_SDK_CACHE:-$_FID_ROOT/.cache/flutter-sdk-$FLUTTER_VERSION}"

flutter_in_docker() {
  local app="$1" pub="$2" cmd="$3"
  [ -n "$FLUTTER_VERSION" ] || { echo "flutter_in_docker: no Flutter version (set FLUTTER_VERSION)" >&2; return 2; }
  mkdir -p "$pub" "$FLUTTER_SDK_CACHE"
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -e PUB_CACHE=/tmp/.pub-cache \
    -v "$FLUTTER_SDK_CACHE":/sdk \
    -v "$app":/app \
    -v "$pub":/tmp/.pub-cache \
    -w /app \
    "$FLUTTER_IMAGE" \
    bash -c "set -e
      if [ ! -x /sdk/flutter/bin/flutter ]; then
        echo \"flutter_in_docker: cloning Flutter $FLUTTER_VERSION into the host cache (once)\" >&2
        rm -rf /sdk/flutter
        git clone --quiet --depth 1 --branch '$FLUTTER_VERSION' https://github.com/flutter/flutter.git /sdk/flutter
      fi
      export PATH=/sdk/flutter/bin:\$PATH
      $cmd"
}
