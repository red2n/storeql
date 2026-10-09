# Release process

How StoreQL cuts a version, and what happens automatically once you do. Read this before pushing a `v*` tag.

## Tag format

One git tag versions the **whole monorepo snapshot** — not per-service. Strict SemVer, always 3 parts:

```
vMAJOR.MINOR.PATCH        e.g. v0.1.0, v1.4.2
```

`vMAJOR.MINOR.PATCH` is the **only** pattern both [`docker-publish.yml`](../.github/workflows/docker-publish.yml) and [`release.yml`](../.github/workflows/release.yml) trigger on. They must stay in sync — a tag that only matches one of them cuts a GitHub Release with no matching Docker images, or vice versa. Don't push a loose tag like `v1` or `v1.0` expecting either workflow to fire.

- **MAJOR** — a breaking API/event-contract change, or a change that requires coordinated redeploy across services (rare; this is an internal platform, not a published library).
- **MINOR** — a new service, a new endpoint surface, a new event, a notable feature.
- **PATCH** — a bug fix, a dependency bump, a docs/manifest-only change.

Maven module versions are **not** bumped to match — every `pom.xml` stays `0.1.0-SNAPSHOT` regardless of what git tag is cut. The tag drives Docker image tags and the GitHub Release; it's intentionally decoupled from Maven's own versioning so cutting a release never means a 20-module version-bump commit.

## Cutting a release

1. Confirm `main`'s latest CI run and Docker publish run are both green (`gh run list --branch main --limit 5`).
2. Tag and push:
   ```
   git tag v0.2.0 -m "Delivery area management + fulfilment resolution"
   git push origin v0.2.0
   ```
3. Two workflows fire off that tag push:
   - **`release.yml`** — rebuilds, deploys module JARs to GitHub Packages (Maven), collects every `target/*.jar` and attaches them to a new GitHub Release named after the tag.
   - **`docker-publish.yml`** — builds and pushes every service + the web image to GHCR, each tagged with `<version>`, `<major>.<minor>`, `<major>`, and `sha-<short>` (in addition to what every push already gets: `sha-<short>`, plus `latest` on `main`). See [Image tags](#image-tags-per-push) below.
   Both also say what the release is made of and where it was built — see [Verifying a release](#verifying-a-release-what-it-is-made-of-and-where-it-was-built).
4. Watch it: `gh run watch --repo red2n/storeql <run-id>`, or check the Releases page / GHCR packages once both finish.

## Image tags per push

Every push to `main` and every `v*.*.*` tag push publish images — the tag *shape* differs by trigger:

| Trigger | Tags applied to each image |
|---|---|
| Push to `main` | `sha-<short>`, `latest` |
| Push to a feature branch (`workflow_dispatch`) | `sha-<short>`, `<branch-name>` |
| Push tag `vX.Y.Z` | `sha-<short>`, `X.Y.Z`, `X.Y`, `X` |

## Verifying a release: what it is made of, and where it was built

Every image `docker-publish.yml` pushes is built with BuildKit's own SBOM and max-mode provenance, and then gets three things bound to its **digest** (a tag can be moved; a digest cannot):

| What | Made by | Answers |
|---|---|---|
| CycloneDX SBOM attestation | syft (`anchore/sbom-action`) + `actions/attest-sbom` | what is in this image — every OS package and every jar (the web image also carries the app's Dart packages from `pubspec.lock`) |
| SLSA build-provenance attestation | `actions/attest-build-provenance` | which repository, workflow, commit and runner built it |
| Sigstore signature, keyless | `cosign sign` | that this repository's publish workflow vouches for it — no private key exists to steal; the ten-minute certificate names the workflow and ref, and the signature is in the public transparency log |

`release.yml` attaches `storeql-<tag>.cdx.json` / `.cdx.xml` (one CycloneDX 1.6 SBOM for the whole reactor: every module and every dependency that ships — `scripts/sbom.sh` makes the same document locally) and `SHA256SUMS`, and attests build provenance and the SBOM to every jar.

To check a release before running it:

```
scripts/verify-release.sh 0.2.0            # all 15 images of that version
scripts/verify-release.sh latest gateway   # one image
gh attestation verify gateway.jar --repo red2n/storeql
```

The script resolves each tag to a digest once, then requires the signature to come from `red2n/storeql`'s `docker-publish.yml` (GitHub's OIDC issuer) and both attestations to verify against that digest; it exits non-zero if anything did not. A cluster can enforce the same rule at admission (Kyverno `verifyImages` or Sigstore's policy-controller) with that identity and issuer.

`scripts/supply-chain-check.py` runs in CI and fails the build if either workflow stops doing any of this — an SBOM switched off, a tag signed instead of a digest, a stored key instead of keyless, an image the verifier does not know; `--self-test` breaks each promise in memory and fails unless the check notices. `scripts/supply-chain-selftest.sh` drives the mechanism end to end against a local registry (build with attestations, sign, attest, verify; then a wrong key, an unsigned image and a moved tag, each refused).

## Known vulnerabilities: scanned before signing, and every night after

`scripts/vuln-scan.sh` (grype) is one script with three callers:

| Where | What it scans | When |
|---|---|---|
| `docker-publish.yml` | each pushed image by digest — OS packages and everything in it | after the push, **before** the SBOM is attested and the image signed: an image with a High or Critical finding is never signed, so `verify-release.sh` and an admission policy refuse it |
| `vulnerability-scan.yml` | the reactor's SBOM (every shipped jar) and the app's Dart packages; on main and nightly also all fifteen published images | pull requests, pushes to main, and 03:17 UTC every night — advisories arrive after the build, so what shipped clean does not stay clean |
| a desk | `scripts/vuln-scan.sh deps`, `… image <ref>`, `… sbom <file>` | before pushing |

The scan fails at **High**. The only way past a finding is an entry in [`security/vulnerability-exceptions.yaml`](../security/vulnerability-exceptions.yaml): the advisory, the package, a reason that is a sentence, who decided, and an expiry at most ninety days out — the day after, the scan fails again and the decision is made afresh. An entry with no advisory, no reason, no name or a lapsed date stops CI (`scripts/vuln-scan.sh exceptions`). `scripts/vuln-scan-selftest.sh` shows the gate refusing Log4Shell by name and the exceptions file refusing what is not a decision. Findings also go to the repository's code scanning.

Fixes arrive as pull requests: `.github/dependabot.yml` watches Maven, the app's pub packages, the Dockerfiles' base images and the workflows' actions, weekly and grouped; security updates come as soon as an advisory names a version in use (switch on *Dependabot security updates* in the repository settings). Where Helidon's parent manages a vulnerable version, the parent pom raises Helidon's own version property and names the advisory beside it, so the line can go when Helidon catches up.

## Registry retention (why GHCR doesn't fill up)

`docker-publish.yml`'s `cleanup` job runs after every publish and keeps only the **2 most recent tagged versions** per package (`keep-n-tagged: 2`), deleting older versions and any untagged/dangling manifests. That means:

- You can always roll back **one** build of `main` (the previous `latest`).
- **A released version is never pruned.** An image carrying a version tag (`0.1.0`: three dotted numbers, which no other tag this workflow makes has) is excluded from the cleanup (`exclude-tags: '*.*.*'`, checked by `scripts/supply-chain-check.py`), and the manual `scripts/ghcr-prune.sh` skips it the same way. A customer's rollback target stays pullable, with its attestations and signature. Builds of `main` between releases still age out.
- Signatures and attestations are stored in GHCR as children of the image they belong to. The cleanup action treats them that way: they stay while their image stays and go when it goes (`delete-orphaned-images` sweeps any whose image is already gone), so keeping two versions keeps two *verifiable* versions.
- Cleanup runs with `if: always()`, so one flaky service image in a publish run doesn't leave the other 14 packages un-pruned.

## Deploying a release to a pilot or production host

A host runs a **named release**, never `latest`: `STOREQL_TAG=0.1.1` in `.env` selects every image, and `docker-compose.prod.yml` refuses to start without it. Set up once per host: `STOREQL_ENV=prod` in `.env` (what the host says it is; `scripts/redeploy.sh --wipe-data` is refused unless it is `dev` or `ci`), and the two data volumes created by hand, `docker volume create storeql_pgdata storeql_backups`, which the prod overlay declares **external** so `docker compose down -v` or a changed project name cannot remove them (bind `storeql_backups` to a disk of its own).

```
scripts/deploy-release.sh --check 0.1.1     # pulls the images, checks the volumes and the host; touches nothing
scripts/deploy-release.sh 0.1.1             # the upgrade, in a closed window
scripts/deploy-release.sh --rollback        # back to the release the host ran before the last upgrade
```

The upgrade takes a **backup and verifies it** (checksum, decryption, `pg_restore` reading it end to end) before anything is stopped, stops the edge so nobody is served half-migrated, starts every other service on the new images (each migrates its own schema first in `strict` mode and is not ready until it has), waits for them, starts the edge, makes a smoke request, and only then records the release (`.storeql-deploy/release`, with the previous one and a line per deploy in `history.jsonl`). A failure after the edge is stopped undoes nothing by itself: it prints the exact rollback line and the backup to restore. **Rolling back is running the previous images**, which is safe because every migration since the first tag is additive; the one exception is a release carrying a `-- storeql:contract` migration, which an older image cannot run on, and that rolls back by restoring the pre-upgrade backup (`docs/BACKUP-AND-RESTORE.md`). `scripts/deploy-release-selftest.sh` proves the order and every refusal against a fake `docker`; the policy is [intent/forward-only-migrations](../intent/forward-only-migrations.md).

## Why `docker-publish.yml` builds per-service, not per-run

Each service gets its own matrix job (its own fresh runner, its own disk) instead of all 15 images being built sequentially in one job off a single full-reactor build. The old single-job design accumulated Maven `target/` output and Docker layers across all 15 builds on one runner with nothing freed mid-job, and eventually exhausted the runner's disk — failing the publish for every service, not just the one that tipped it over. See the comment block at the top of `docker-publish.yml` for the full rationale.
