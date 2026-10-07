#!/usr/bin/env bash
# Checks the alert rules and the Prometheus and Alertmanager configs with the tools that read them
# (promtool, amtool) and runs the rule unit tests in infra/prometheus/tests: the compose copies, and
# the copies embedded in the Kubernetes ConfigMaps. Needs docker and python3 (with PyYAML). Run before
# changing infra/prometheus, infra/alertmanager or the k8s observability manifests, and in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
# The versions the stack runs, read from the compose file so there is one place to raise them.
PROM="$(grep -oE 'prom/prometheus:v[0-9.]+' docker-compose.yml | head -1)"
AM="$(grep -oE 'prom/alertmanager:v[0-9.]+' docker-compose.yml | head -1)"
[ -n "$PROM" ] && [ -n "$AM" ] || { echo "cannot read the Prometheus and Alertmanager images from docker-compose.yml" >&2; exit 1; }

# 1. Compose: the config with the rules mounted where it says they are, so its file pattern matches real
#    files (promtool then checks them), the rules on their own, the unit tests, the Alertmanager config.
docker run --rm --entrypoint promtool \
  -v "$PWD/infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  -v "$PWD/infra/prometheus/rules:/etc/prometheus/rules:ro" "$PROM" check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint promtool -v "$PWD/infra/prometheus:/p:ro" "$PROM" test rules /p/tests/alerts.test.yml
docker run --rm --entrypoint amtool -v "$PWD/infra/alertmanager:/a:ro" "$AM" check-config /a/alertmanager.yml

# 2. Kubernetes: the same checks on the files the pods will actually read, taken out of the ConfigMaps.
K8S="$(mktemp -d)"; trap 'rm -rf "$K8S"' EXIT
python3 - "$K8S" <<'PY'
import os, sys, yaml
out = sys.argv[1]
os.makedirs(f"{out}/rules")
want = {"storeql-prometheus-config": ("prometheus.yml", "prometheus.yml"),
        "storeql-alertmanager-config": ("alertmanager.yml", "alertmanager.yml"),
        "storeql-prometheus-rules": ("alerts.yml", "rules/alerts.yml")}
found = set()
for doc in yaml.safe_load_all(open("k8s/02-configmaps.yaml")):
    if doc and doc.get("kind") == "ConfigMap" and doc["metadata"]["name"] in want:
        key, dest = want[doc["metadata"]["name"]]
        open(f"{out}/{dest}", "w").write(doc["data"][key])
        found.add(doc["metadata"]["name"])
missing = set(want) - found
if missing:
    sys.exit(f"k8s/02-configmaps.yaml lacks the ConfigMaps {sorted(missing)}")
PY
chmod -R a+rX "$K8S" # the tools run as another user inside the container
docker run --rm --entrypoint promtool \
  -v "$K8S/prometheus.yml:/etc/prometheus/prometheus.yml:ro" -v "$K8S/rules:/etc/prometheus/rules:ro" \
  "$PROM" check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint amtool -v "$K8S:/a:ro" "$AM" check-config /a/alertmanager.yml
