#!/usr/bin/env bash
# The container healthcheck of every StoreQL Java image: asks the service's own health probe on
# localhost and exits 0 only when it answers 200.
#
#   healthcheck <path> [port]        e.g. healthcheck /health/live       (port defaults to 8080)
#
# It needs nothing but bash (a TCP connection through /dev/tcp) and `timeout`, because the Temurin 25
# JRE image ships neither curl, wget nor nc: a healthcheck that used one would report every service
# unhealthy on that base and no dependent would start (the 18 Sep 2026 revert). The answer is read from the
# status line alone, as HTTP/1.1 with Connection: close (Helidon 4, which every StoreQL service runs,
# answers an HTTP/1.0 request with 505, so a 1.0 healthcheck is unhealthy on the real stack), and
# nothing waits longer than 4 s.
set -u
path="${1:?usage: healthcheck <path> [port]}"
port="${2:-8080}"

# Any failure is exit 1, the code Docker reads as "unhealthy" (timeout's own 124 and bash's others are
# folded into it; 2 is reserved by Docker).
if timeout 4 bash -c '
  exec 3<>"/dev/tcp/127.0.0.1/$2" || exit 1
  printf "GET %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" "$1" >&3
  IFS= read -r status <&3 || exit 1
  [[ $status == "HTTP/1."?" 200"* ]]
' healthcheck "$path" "$port" 2>/dev/null; then
  exit 0
fi
exit 1
