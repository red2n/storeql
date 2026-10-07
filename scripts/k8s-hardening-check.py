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

# The alert rules Prometheus loads on Kubernetes are a copy of the file the compose stack mounts; a copy
# that drifts would alert on different things in each place.
rules_cm = [d for _, d in docs if d["kind"] == "ConfigMap" and d["metadata"]["name"] == "storeql-prometheus-rules"]
need(len(rules_cm) == 1, "the ConfigMap storeql-prometheus-rules exists")
if rules_cm:
    need(
        rules_cm[0]["data"].get("alerts.yml", "").strip() == open("infra/prometheus/rules/alerts.yml").read().strip(),
        "storeql-prometheus-rules alerts.yml equals infra/prometheus/rules/alerts.yml",
    )
alertmanager = [d for _, d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "alertmanager"]
need(len(alertmanager) == 1, "Alertmanager is deployed")
need(
    any(p["spec"].get("podSelector") == {"matchLabels": {"app": "alertmanager"}} for p in policies),
    "a NetworkPolicy for Alertmanager",
)

# The wiring that was once missing altogether (Prometheus loaded no rules and had no Alertmanager): the
# config names the rules and the Alertmanager, the pod mounts the rules, and the network lets the
# alerts through in both directions.
prom_cm = [d for _, d in docs if d["kind"] == "ConfigMap" and d["metadata"]["name"] == "storeql-prometheus-config"]
if prom_cm:
    prom_cfg = yaml.safe_load(prom_cm[0]["data"]["prometheus.yml"])
    need("/etc/prometheus/rules/*.yml" in prom_cfg.get("rule_files", []), "Prometheus loads /etc/prometheus/rules/*.yml (rule_files)")
    targets = [t for am in prom_cfg.get("alerting", {}).get("alertmanagers", []) for sc in am.get("static_configs", []) for t in sc.get("targets", [])]
    need("alertmanager:9093" in targets, "Prometheus sends alerts to alertmanager:9093 (alerting)")
prom_dep = [d for _, d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "prometheus"]
if prom_dep:
    pspec = prom_dep[0]["spec"]["template"]["spec"]
    need(any(m.get("mountPath") == "/etc/prometheus/rules" for c in pspec["containers"] for m in c.get("volumeMounts", [])), "Prometheus mounts the rules at /etc/prometheus/rules")
    need(any(v.get("configMap", {}).get("name") == "storeql-prometheus-rules" for v in pspec.get("volumes", [])), "Prometheus's rules volume is storeql-prometheus-rules")
prom_pol = [p for p in policies if p["spec"].get("podSelector") == {"matchLabels": {"app": "prometheus"}}]
need(
    any(port.get("port") == 9093 for p in prom_pol for rule in p["spec"].get("egress", []) for port in rule.get("ports", [])),
    "the Prometheus NetworkPolicy lets it reach Alertmanager on 9093",
)
am_pol = [p for p in policies if p["spec"].get("podSelector") == {"matchLabels": {"app": "alertmanager"}}]
need(
    any(
        f.get("podSelector", {}).get("matchLabels", {}).get("app") == "prometheus" and port.get("port") == 9093
        for p in am_pol for rule in p["spec"].get("ingress", []) for f in rule.get("from", []) for port in rule.get("ports", [])
    ),
    "the Alertmanager NetworkPolicy admits Prometheus on 9093",
)

if problems:
    print("k8s hardening: %d problem(s)" % len(problems))
    for p in problems:
        print(" -", p)
    sys.exit(1)
print("k8s hardening: %d workloads, %d network policies, namespace restricted — all checks pass" % (len(workloads), len(policies)))
