#!/usr/bin/env python3
"""A container healthcheck may use only what its image carries (intent/jdk-25.md).

The Temurin 25 JRE image ships neither curl, wget nor nc. On 18 Sep 2026 a bot moved the base alone,
every compose healthcheck was still `curl`, no service reported healthy and the whole stack failed to
start, while the application inside was perfectly well. This check reads the files and fails when it
could happen again:

  * a compose service built from Dockerfile.svc must have a healthcheck of the form
    ["CMD", "healthcheck", "/health/..."] (infra/healthcheck.sh, baked into the image: bash only),
    never CMD-SHELL and never curl, wget, nc or ncat;
  * a Kubernetes workload running one of our Java images must probe with httpGet, never exec (an exec
    probe runs a binary inside the image, which is the same trap).

Usage: scripts/compose-healthcheck-check.py              check docker-compose*.yml and k8s/*.yaml
       scripts/compose-healthcheck-check.py --self-test  break each promise in memory; fails unless noticed
"""
import glob
import os
import re
import sys

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FORBIDDEN = re.compile(r"(?<![\w-])(curl|wget|nc|ncat|telnet)(?![\w-])")
NOT_JAVA = ("storeql-web", "storeql-backup")  # nginx and Postgres images: they carry their own tools
PROBES = ("startupProbe", "readinessProbe", "livenessProbe")


def builds_svc(service):
    build = service.get("build")
    if isinstance(build, str):
        return build.endswith("Dockerfile.svc")
    return isinstance(build, dict) and str(build.get("dockerfile", "")).endswith("Dockerfile.svc")


def check_compose(name, doc):
    problems = []
    for svc, spec in (doc.get("services") or {}).items():
        if not builds_svc(spec):
            continue
        hc = spec.get("healthcheck")
        if hc is None:
            if name.endswith("docker-compose.yml"):
                problems.append(f"{name}: {svc} is built from Dockerfile.svc and has no healthcheck")
            continue
        test = hc.get("test")
        if not isinstance(test, list):
            problems.append(f"{name}: {svc} healthcheck test must be a list, not a string")
            continue
        if FORBIDDEN.search(" ".join(map(str, test))):
            problems.append(f"{name}: {svc} healthcheck uses a tool the image does not carry: {test}")
        if test[:2] != ["CMD", "healthcheck"]:
            problems.append(f'{name}: {svc} healthcheck must be ["CMD", "healthcheck", "/health/..."], got {test}')
    return problems


def check_k8s(name, docs):
    problems = []
    for doc in docs:
        if not isinstance(doc, dict):
            continue
        spec = doc.get("spec") or {}
        pod = (spec.get("template") or {}).get("spec") or (((spec.get("jobTemplate") or {}).get("spec") or {}).get("template") or {}).get("spec") or {}
        for c in (pod.get("containers") or []):
            image = str(c.get("image", ""))
            if "/storeql-" not in image or any(f"/{n}:" in image for n in NOT_JAVA):
                continue
            for probe in PROBES:
                if "exec" in (c.get(probe) or {}):
                    problems.append(f"{name}: {doc.get('metadata', {}).get('name')} {c.get('name')} {probe} uses exec; use httpGet")
    return problems


def run():
    problems = []
    for path in sorted(glob.glob(os.path.join(ROOT, "docker-compose*.yml"))):
        with open(path) as f:
            problems += check_compose(os.path.relpath(path, ROOT), yaml.safe_load(f) or {})
    for path in sorted(glob.glob(os.path.join(ROOT, "k8s", "*.yaml"))):
        with open(path) as f:
            problems += check_k8s(os.path.relpath(path, ROOT), list(yaml.safe_load_all(f)))
    return problems


def self_test():
    good = {"services": {"a-svc": {"build": {"dockerfile": "Dockerfile.svc"},
                                   "healthcheck": {"test": ["CMD", "healthcheck", "/health/live"]}}}}
    assert not check_compose("docker-compose.yml", good), "a clean file must pass"
    for label, test in (
        ("curl in CMD-SHELL", ["CMD-SHELL", "curl -fsS http://localhost:8080/health/live || exit 1"]),
        ("curl in CMD", ["CMD", "curl", "-f", "http://localhost:8080/health/live"]),
        ("wget", ["CMD", "wget", "-q", "-O", "-", "http://localhost:8080/health/live"]),
        ("nc", ["CMD-SHELL", "nc -z localhost 8080"]),
        ("a string", "curl -f http://localhost:8080/health/live"),
        ("no healthcheck wrapper", ["CMD", "java", "-version"]),
    ):
        bad = {"services": {"a-svc": {"build": {"dockerfile": "Dockerfile.svc"}, "healthcheck": {"test": test}}}}
        assert check_compose("docker-compose.yml", bad), f"must fail: {label}"
    none = {"services": {"a-svc": {"build": {"dockerfile": "Dockerfile.svc"}}}}
    assert check_compose("docker-compose.yml", none), "must fail: no healthcheck at all"
    other = {"services": {"pg": {"image": "postgres:16-alpine", "healthcheck": {"test": ["CMD-SHELL", "pg_isready"]}}}}
    assert not check_compose("docker-compose.yml", other), "another image's own tool is fine"
    deploy = lambda image, probe: [{"kind": "Deployment", "metadata": {"name": "x"}, "spec": {"template": {"spec": {"containers": [
        {"name": "x", "image": image, "readinessProbe": probe}]}}}}]
    assert not check_k8s("k8s/x.yaml", deploy("ghcr.io/red2n/storeql-iam-svc:latest", {"httpGet": {"path": "/health/ready", "port": 8080}}))
    assert check_k8s("k8s/x.yaml", deploy("ghcr.io/red2n/storeql-iam-svc:latest", {"exec": {"command": ["curl", "localhost"]}})), "exec on a Java image must fail"
    assert not check_k8s("k8s/x.yaml", deploy("redis:7-alpine", {"exec": {"command": ["redis-cli", "ping"]}})), "another image's exec is fine"
    assert not check_k8s("k8s/x.yaml", deploy("ghcr.io/red2n/storeql-backup:latest", {"exec": {"command": ["true"]}})), "the backup image is not a Java image"
    print("compose healthcheck check: self-test passed")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
        sys.exit(0)
    found = run()
    for p in found:
        print(p, file=sys.stderr)
    if found:
        sys.exit(1)
    print("compose healthcheck check: every Java image's healthcheck uses only what it carries")
