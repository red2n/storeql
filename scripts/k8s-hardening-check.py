#!/usr/bin/env python3
"""The Kubernetes hardening the manifests must keep (Pod Security Standards restricted; a
default-deny NetworkPolicy; CIS Kubernetes Benchmark 5.2 and 5.3): fails with a list of what is
missing, so CI refuses a manifest that lets it slip. Run: scripts/k8s-check.sh"""
import glob
import sys

import yaml

SERVICE_IMAGES = ("ghcr.io/red2n/storeql-",)
problems = []


def need(cond, what):
    if not cond:
        problems.append(what)


docs = []
for f in sorted(glob.glob("k8s/*.yaml")):
    for d in yaml.safe_load_all(open(f)):
        if isinstance(d, dict) and d.get("kind"):
            docs.append((f, d))

namespaces = {d["metadata"]["name"]: d for _, d in docs if d["kind"] == "Namespace"}
need("storeql" in namespaces, "namespace storeql is declared")
labels = namespaces.get("storeql", {}).get("metadata", {}).get("labels", {})
for mode in ("enforce", "warn", "audit"):
    need(labels.get(f"pod-security.kubernetes.io/{mode}") == "restricted", f"namespace storeql enforces PSS restricted ({mode})")

policies = [d for _, d in docs if d["kind"] == "NetworkPolicy" and d["metadata"].get("namespace") == "storeql"]
deny = [p for p in policies if p["spec"].get("podSelector") == {} and set(p["spec"].get("policyTypes", [])) == {"Ingress", "Egress"} and not p["spec"].get("ingress") and not p["spec"].get("egress")]
need(len(deny) == 1, "one default-deny NetworkPolicy (empty podSelector, Ingress+Egress, no rules) in storeql")
postgres = [p for p in policies if p["spec"].get("podSelector") == {"matchLabels": {"app": "postgres"}}]
need(postgres and all(f.get("podSelector", {}).get("matchLabels", {}).get("app") in ("pgbouncer", "postgres-exporter", "storeql-backup") for rule in postgres[0]["spec"].get("ingress", []) for f in rule.get("from", [])), "postgres ingress is from pgbouncer, its exporter and the backup job only")

workloads = [(f, d) for f, d in docs if d["kind"] in ("Deployment", "StatefulSet", "DaemonSet", "Job")]
need(len(workloads) >= 20, f"workloads found ({len(workloads)})")
for f, d in workloads:
    name = d["metadata"]["name"]
    ns = d["metadata"].get("namespace")
    tmpl = d["spec"]["template"]
    spec = tmpl["spec"]
    where = f"{name} ({ns})"
    if ns == "storeql":
        need(not spec.get("hostNetwork") and not spec.get("hostPID") and not spec.get("hostIPC"), f"{where}: no host namespaces")
        need(not any("hostPath" in v for v in spec.get("volumes", [])), f"{where}: no hostPath volume")
    psc = spec.get("securityContext", {})
    need(psc.get("runAsNonRoot") is True, f"{where}: pod runAsNonRoot")
    need(isinstance(psc.get("runAsUser"), int) and psc.get("runAsUser") > 0, f"{where}: pod runAsUser is a numeric non-root id")
    need(psc.get("seccompProfile", {}).get("type") == "RuntimeDefault", f"{where}: pod seccompProfile RuntimeDefault")
    for c in spec.get("containers", []) + spec.get("initContainers", []):
        csc = c.get("securityContext", {})
        cwhere = f"{where} container {c['name']}"
        need(csc.get("allowPrivilegeEscalation") is False, f"{cwhere}: allowPrivilegeEscalation false")
        need(csc.get("capabilities", {}).get("drop") == ["ALL"], f"{cwhere}: capabilities drop ALL")
        need(not csc.get("privileged"), f"{cwhere}: not privileged")
        if c.get("image", "").startswith(SERVICE_IMAGES):
            need(csc.get("readOnlyRootFilesystem") is True, f"{cwhere}: the platform's own image runs on a read-only root filesystem")
            need(any(m.get("mountPath") == "/tmp" for m in c.get("volumeMounts", [])), f"{cwhere}: /tmp is an emptyDir")
        if name in ("storeql-app",):
            need(any(m.get("mountPath") == "/etc/nginx/conf.d" for m in c.get("volumeMounts", [])), f"{cwhere}: nginx renders its config into an emptyDir")
    volume_names = {v["name"] for v in spec.get("volumes", [])} | {t["metadata"]["name"] for t in d["spec"].get("volumeClaimTemplates", [])}
    for m in [m for c in spec.get("containers", []) for m in c.get("volumeMounts", [])]:
        need(m["name"] in volume_names, f"{where}: mount {m['name']} has a volume or a claim template")

if problems:
    print("k8s hardening: %d problem(s)" % len(problems))
    for p in problems:
        print(" -", p)
    sys.exit(1)
print("k8s hardening: %d workloads, %d network policies, namespace restricted — all checks pass" % (len(workloads), len(policies)))
