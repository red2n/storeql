# JDK 25: build, test and run on the current LTS

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, completion plan P0 / round R3b · 2026-10-08 |
| **Roadmap** | new: the owner's R3b, "JDK 25 LTS: Dockerfile, healthchecks without curl, CI, k8s, full regression" (the 18 Sep bump was reverted, see Problem) |
| **Services** | none owns data: the platform (gateway, config, discovery) and all twelve business services change how they are built and run |
| **Builds on** | `Dockerfile.svc`, the `x-svc-healthcheck` anchor and the gateway and config healthchecks in `docker-compose.yml`, the four workflows that name a JDK, `.github/dependabot.yml`, the virtual-thread rule in [CLAUDE.md](../CLAUDE.md) and [ARCHITECTURE §596](../docs/ARCHITECTURE.md) |
| **Built in** | (not yet built) |

## Problem

The platform builds, tests and runs on Java 21, the previous long-term-support release. Java 25 is the current LTS (September 2025), is the release Helidon 4.5 recommends, and is where fixes will keep arriving once 21 ages out. On 18 September a bot bumped the service image to `eclipse-temurin:25-jre` and the bump was reverted: the 25 image ships **no `curl`, `wget` or `nc`**, every compose healthcheck was `curl`, so on the new base no service ever reported healthy, nothing that depends on them started, and the whole stack (which is how the k6 suites run) failed to come up, while the application inside was perfectly well. Kubernetes was never affected (its probes are `httpGet`). Staying on 21 is not wrong today; moving later, under pressure, with a bot proposing the bump alone, is.

## Outcome

Build, tests and the fourteen Java images run on Temurin 25. The jars still target release 21, so going back is one line (the `FROM`). The container healthchecks use a small script baked into the image, which needs only `bash`, so a base that loses a tool cannot silence the stack again, and a check in CI fails a compose healthcheck that uses a tool its image does not carry. The stack comes up healthy and both flow-guard suites and the full k6 run pass on 25.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the developer and the person who deploys; no shopper, cashier or manager sees a change.
- **Channels:** build, CI, container images; the platform console is unchanged.
- **Scope:** the whole reactor and every Java image. Non-Java images (nginx, backup, Postgres, Kafka, Redis, Consul) are untouched.
- **Roles that can write:** none (no API).
- **Sandbox tenant:** not applicable; no tenant data is read or written.

## Scope

- **In:** the service image on `eclipse-temurin:25-jre-noble`; `infra/healthcheck.sh` baked into it and used by the three compose healthcheck definitions; the four workflows on JDK 25; `--enable-native-access=ALL-UNNAMED` in the image entrypoint; `-XX:+EnableDynamicAgentLoading` for the test JVM; Dependabot told not to propose non-LTS Java bases; a static check and a self-test for the healthcheck; docs, skills and scripts that say "JDK 21" or a fixed 21 `JAVA_HOME`.
- **Out, on purpose:**
  - **Raising `maven.compiler.release` above 21.** Nothing in 22 to 25 is needed, release-21 bytecode runs on both, and it keeps the rollback a one-line `FROM`. Revisit after 25 has run in production; then release, Helidon's `maven.compiler.source` and PMD's `targetJdk` move together.
  - **Relaxing the "no monitor around blocking work" rule.** JEP 491 stops `synchronized` pinning a virtual thread from 24, but the rule also protects the 21 rollback and costs nothing. It stays; only its wording changes (see Decisions).
  - **Compact object headers, the AOT cache, generational ZGC.** Available on 25 and worth measuring, each as its own intent page with k6 numbers; none is part of moving.
  - **Replacing Guava or Netty to silence `sun.misc.Unsafe` warnings.** Library-owned, warn only on 25; revisit with 26.
  - **Kubernetes manifests.** Every Java workload already uses `httpGet`; a guard keeps it so (see Acceptance).

## Data and flow

- **Owned by** none: no table, event, endpoint or error code.
- **Needs from other services:** none.
- **Events published:** none.
- **Retryable writes** (Idempotency-Key): none.
- **New error codes:** none.

## Money, time and limits

- **Currency, ledger postings, plan limits:** none.
- **Dates:** none recorded. JDK 25 ships newer locale data (CLDR 47, against 42 on 21): formatted money, dates and month names in templates and exports can change spelling or spacing. The tests that pin such strings run on 25 first; a difference is a decision about the text, never a test edit that hides it.

## Constraints

- Golden rule 12 (three probes: started, live, ready) is unchanged; only the container-level healthcheck that asks the live probe changes.
- A base-image move is a deliberate change, never a bot's bump: the healthcheck and the `FROM` change in one commit, and the stack is brought up and swept afterwards.
- The scan gate: a new base changes the operating-system package set `scripts/vuln-scan.sh` fails on at High, so the image is scanned before the change merges.
- Rollback must stay one line: release 21 jars, a healthcheck that does not depend on the base's tools.

## Open questions

1. **Pin the base by operating-system release, or follow the floating tag?** The floating `25-jre` already points at Ubuntu 26.04 (Rust coreutils); `25-jre-noble` is Ubuntu 24.04 with the same Temurin 25. Recommended: pin `25-jre-noble`. → **`25-jre-noble`** (by industry standard: pin the LTS and the OS release, so the operating system never moves under a version bump; Dependabot's digest bumps then stay reviewable. Claude, 2026-10-08)
2. **Raise the compiler release to 25?** Recommended: no. → **No, stays 21** (by industry standard: build on the new JDK, target the older release until the new one has run in production. Claude, 2026-10-08)
3. **Does anyone run the jars outside this repository's compose and Kubernetes?** The repo cannot say; keeping release 21 keeps the answer from mattering. → **Not needed to decide** (Claude, 2026-10-08)
4. **Which JVM warnings on 25 are acceptable?** Recommended: native-access warnings silenced by the flag that grants it; the `sun.misc.Unsafe` warnings from Guava and Netty accepted (they only warn, and the owners are the libraries). → **As recommended** (by industry standard. Claude, 2026-10-08)

## Acceptance

- [x] Every module's `mvn verify` (format, Checkstyle, PMD, SpotBugs, ArchUnit, unit and integration tests) is green on JDK 25 with release 21 — module by module on a purged Docker; all 22 modules green on 25, 5,754 tests, 0 SpotBugs findings, 0 Checkstyle violations (8-9 Oct 2026).
- [x] The healthcheck answers 0 only for a `200`: a `200` is healthy; a `503`, a refused connection and a server that never answers (within 4 seconds) are not — `scripts/healthcheck-selftest.sh`, on both the 21 and 25 bases (21 of 21, against a server that answers HTTP/1.0 with 505 as Helidon does).
- [x] A compose healthcheck of a service built from `Dockerfile.svc` that uses `curl`, `wget` or `nc` fails the check with a message naming the service; a clean file passes — `scripts/compose-healthcheck-check.py` and its self-test, run by CI.
- [x] Every Java workload in `k8s/` uses `httpGet` probes only; an `exec` probe on one fails the same check.
- [x] The base image scans clean at High: `scripts/vuln-scan.sh image` on the built service image, clean on `eclipse-temurin:25-jre-noble` and on the built inventory-svc image, no exception needed.
- [x] The stack comes up on the 25 images with every service healthy (32 healthy, the 6 without a healthcheck are the ones the backlog lists), then `flow-guard-comprehensive` (124 of 124) and `flow-guard-runtime` (79 of 79) pass.
- [ ] The full k6 run passes on 25 (not yet run: it is run once on the integrated tree); the boot logs of all 14 services are read for new warnings and each is accepted or silenced on this page — read 9 Oct 2026: every service prints one JDK warning (Guava's `AbstractFuture` calls the terminally deprecated `sun.misc.Unsafe::objectFieldOffset`; library-owned, accepted), no `Unrecognized VM option`, no class-version error; payment and customer also log Jersey's `ApiResponse<?> is not resolvable to a concrete type` for `CashMovementResource.getZReport/listMovements` and `LoyaltyProgrammeResource.programme`, which is not about the JDK and is on the housekeeping list.
- [ ] The four workflows use JDK 25 and CI is green; the jars they build still run on 21 (`java -jar` of one service on the 21 image boots and answers `/health/live`).
- [x] The tests that pin locale-dependent strings (notification-svc templates, money in words, fiscal and export formats) are green on 25 with no difference (notification-svc 244, common-service 178, einvoice 213).

## Screens

None.

## Decisions

- **The healthcheck is a bash script over `/dev/tcp`, not a Java class and not `curl` installed back.** The JRE image has `bash`, `timeout`, `head` and `grep`. A Java class pays JVM start-up on every probe (and `JAVA_TOOL_OPTIONS` makes every `java` print its banner); reinstalling `curl` re-imports its vulnerability surface and breaks again on the next base. Tested against a local server: 0 on 200, 1 on 503, 1 on refused, about 3 ms each. It is called as `["CMD", "healthcheck", "/health/live"]`, not `CMD-SHELL`, because `sh` has no `/dev/tcp`.
- **The healthcheck speaks HTTP/1.1, not 1.0.** The first stack on the 25 image did not come up: Helidon 4 answers an HTTP/1.0 request with `505 HTTP Version Not Supported`, and the script had asked for 1.0. The self-test's Python server accepted 1.0, so it could not have caught it; it now answers 1.0 with 505 as Helidon does, and the old script fails it. Found only by bringing the real stack up, which is why the acceptance line demands it.
- **Base, healthcheck and CI move in one commit.** The 18 Sep bump changed the base alone and was verified with `java -jar` inside the image; the container healthcheck was never run. This time the stack is brought up and swept.
- **The synchronized rule stays, reworded.** On 25 `synchronized` no longer pins a virtual thread (native frames and class initialisation still do). The rule is kept as defence for the 21 rollback; it is reworded to say so and to point at JFR's `jdk.VirtualThreadPinned` (the `-Djdk.tracePinnedThreads` flag it cited is gone from 24). It is relaxed only after a JFR run under k6 on 25 shows no pins and the 21 fallback is retired.
- **Dependabot proposes no Java base major.** Both open bot branches (`eclipse-temurin-24-jre`, a non-LTS release, and `-25-jre`, the unfixed bump) are closed once this merges; the docker ecosystem keeps its entry with an `ignore` for major versions, so only an LTS move chosen by hand happens.
- **Already proved before building:** on JDK 25 with release 21, inventory-svc (565 tests) and the gateway (372) pass `mvn verify` with format, Checkstyle, PMD and SpotBugs clean and no source change. The only output is JDK 24+ warnings: Guava and Netty call `sun.misc.Unsafe`, and the test agents (Mockito, ByteBuddy) self-attach.

## Flow Tests entry

None: no business flow changes. The catalogue is republished after the tranche as usual.
