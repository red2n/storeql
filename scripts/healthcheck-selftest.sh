#!/usr/bin/env bash
#
# Proves infra/healthcheck.sh, the container healthcheck of every Java image (intent/jdk-25.md): it
# exits 0 only when the service answers 200, and 1 for a 503, a redirect, a refused connection and a
# server that accepts and never answers (within the 4 s it allows itself). It runs the script on this
# machine and, where Docker is available, inside each base image the service image can be built on
# (the current 25 and the 21 it can be rolled back to), because the point of the script is that it needs
# nothing those images lack.
#
# Usage: scripts/healthcheck-selftest.sh        exit 0 only when every check passed
#        HEALTHCHECK_IMAGES="eclipse-temurin:25-jre-noble" scripts/healthcheck-selftest.sh
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT/infra/healthcheck.sh"
IMAGES="${HEALTHCHECK_IMAGES:-eclipse-temurin:21-jre eclipse-temurin:25-jre-noble}"
OK_PORT=18080 SILENT_PORT=18081 REFUSED_PORT=18099
passed=0 failed=0
ok() { printf '  ok    %s\n' "$1"; passed=$((passed + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; failed=$((failed + 1)); }

python3 -I - "$OK_PORT" "$SILENT_PORT" <<'PY' &
import http.server, socket, sys, threading
ok_port, silent_port = int(sys.argv[1]), int(sys.argv[2])
class H(http.server.BaseHTTPRequestHandler):
    # Like Helidon 4, the server every StoreQL service runs: it speaks HTTP/1.1 only and answers an
    # HTTP/1.0 request with 505. A healthcheck that asks for 1.0 is unhealthy on the real stack
    # (found when the first stack on the 25 image would not come up).
    protocol_version = "HTTP/1.1"
    def do_GET(self):
        if self.request_version == "HTTP/1.0":
            self.send_response(505); self.send_header("Content-Length", "0"); self.send_header("Connection", "close"); self.end_headers(); return
        if self.path == "/ok":
            body = b'{"status":"UP"}'; self.send_response(200); self.send_header("Content-Length", str(len(body))); self.send_header("Connection", "close"); self.end_headers(); self.wfile.write(body)
        elif self.path == "/down":
            self.send_response(503); self.send_header("Content-Length", "0"); self.send_header("Connection", "close"); self.end_headers()
        elif self.path == "/redirect":
            self.send_response(302); self.send_header("Location", "/ok"); self.send_header("Content-Length", "0"); self.send_header("Connection", "close"); self.end_headers()
        else:
            self.send_response(404); self.send_header("Content-Length", "0"); self.send_header("Connection", "close"); self.end_headers()
    def log_message(self, *a): pass
threading.Thread(target=http.server.ThreadingHTTPServer(("127.0.0.1", ok_port), H).serve_forever, daemon=True).start()
silent = socket.socket(); silent.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
silent.bind(("127.0.0.1", silent_port)); silent.listen(16)   # accepts, never answers
threading.Event().wait()
PY
SERVER=$!
trap 'kill $SERVER 2>/dev/null; wait $SERVER 2>/dev/null' EXIT
for _ in $(seq 1 50); do (exec 3<>/dev/tcp/127.0.0.1/$OK_PORT) 2>/dev/null && break; sleep 0.1; done

# run_case <where> <expected> <label> <path> <port> [command prefix...]
run_case() {
  local where="$1" want="$2" label="$3" path="$4" port="$5"; shift 5
  local got; "$@" "$path" "$port" >/dev/null 2>&1; got=$?
  if [ "$got" -eq "$want" ]; then ok "$where: $label"; else bad "$where: $label (exit $got, wanted $want)"; fi
}
suite() { # suite <where> <command prefix...>
  local where="$1"; shift
  run_case "$where" 0 "a 200 is healthy" /ok $OK_PORT "$@"
  run_case "$where" 1 "a 503 is not" /down $OK_PORT "$@"
  run_case "$where" 1 "a redirect is not" /redirect $OK_PORT "$@"
  run_case "$where" 1 "a 404 is not" /nothing $OK_PORT "$@"
  run_case "$where" 1 "a refused connection is not" /ok $REFUSED_PORT "$@"
  local t0=$SECONDS
  run_case "$where" 1 "a server that never answers is not, and the wait is bounded" /ok $SILENT_PORT "$@"
  if [ $((SECONDS - t0)) -le 6 ]; then ok "$where: it gave up within 6 s"; else bad "$where: it waited $((SECONDS - t0)) s"; fi
}

suite "this machine" "$SCRIPT"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  for image in $IMAGES; do
    if docker pull -q "$image" >/dev/null 2>&1; then
      suite "$image" docker run --rm --network host -v "$SCRIPT:/usr/local/bin/healthcheck:ro" --entrypoint /usr/local/bin/healthcheck "$image"
    else
      bad "$image could not be pulled"
    fi
  done
else
  echo "  skip  docker is not available: the base images were not tried"
fi
printf '\nhealthcheck self-test: %d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
