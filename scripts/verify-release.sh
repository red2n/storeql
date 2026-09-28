#!/usr/bin/env bash
#
# Verifies what a StoreQL release is made of and where it was built (22.10): for every published
# image, the keyless Sigstore signature, the SLSA build-provenance attestation and the CycloneDX
# SBOM attestation — each bound to the image's digest and to this repository's publish workflow.
# Nothing here trusts a tag: the tag is resolved to a digest once, and the digest is what is checked.
#
# Usage: scripts/verify-release.sh <tag>            e.g. 0.2.0, latest, sha-1a2b3c4
#        scripts/verify-release.sh <tag> gateway    one image only
#        OWNER=someone-else scripts/verify-release.sh <tag>   a fork's images
#
# Needs: cosign (>= 2.4), gh (signed in), and network access to ghcr.io and Sigstore.
# Exit status: 0 only when every check on every image passed.
set -uo pipefail

TAG="${1:-}"
ONLY="${2:-}"
if [ -z "$TAG" ]; then
  sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi
TAG="${TAG#v}"

OWNER="${OWNER:-red2n}"
REPO="${REPO:-${OWNER}/storeql}"
PREFIX="ghcr.io/${OWNER}/storeql"
# The identity a signature must carry: this repository's publish workflow, at a branch or a tag.
IDENTITY="^https://github.com/${REPO}/\.github/workflows/docker-publish\.yml@refs/(heads|tags)/.+$"
ISSUER="https://token.actions.githubusercontent.com"

IMAGES=(gateway config iam-svc tenant-svc product-svc inventory-svc purchase-svc pricing-svc
  cart-svc order-svc payment-svc customer-svc notification-svc reporting-svc web backup)
[ -n "$ONLY" ] && IMAGES=("$ONLY")

for tool in cosign gh; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool not found on PATH" >&2; exit 2; }
done

failed=0
check() { # check <label> <command...>
  local label="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    printf '  ok    %s\n' "$label"
  else
    printf '  FAIL  %s\n' "$label"
    failed=$((failed + 1))
  fi
}

for name in "${IMAGES[@]}"; do
  image="${PREFIX}-${name}"
  digest="$(cosign triangulate --type digest "${image}:${TAG}" 2>/dev/null | sed 's/.*@//')"
  if [[ "$digest" != sha256:* ]]; then
    printf '%s:%s\n  FAIL  the tag does not resolve to a digest\n' "$image" "$TAG"
    failed=$((failed + 1))
    continue
  fi
  ref="${image}@${digest}"
  echo "$ref"
  check "signed by ${REPO}'s publish workflow (Sigstore, keyless)" \
    cosign verify --certificate-identity-regexp "$IDENTITY" --certificate-oidc-issuer "$ISSUER" "$ref"
  check "SLSA build provenance from ${REPO}" \
    gh attestation verify "oci://${ref}" --repo "$REPO" --predicate-type https://slsa.dev/provenance/v1
  check "CycloneDX SBOM attested to this digest" \
    gh attestation verify "oci://${ref}" --repo "$REPO" --predicate-type https://cyclonedx.org/bom
done

if [ "$failed" -gt 0 ]; then
  echo "${failed} check(s) failed: do not run what did not verify." >&2
  exit 1
fi
echo "every image of ${TAG} verified."
