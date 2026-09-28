# API versioning and the sandbox

How StoreQL's API changes without breaking the systems built on it (22.8), and where those systems
rehearse before they touch anything real.

## Versions

Every route lives under a version segment: `/api/v1/{service}/…`. The gateway peels the segment off
and routes to the same service either way; business services are version-agnostic, and a `v2` that
meant a different upstream would branch in one place (`ProxyResource.Route`).

- **Breaking changes ship only as a new version.** A field removed or renamed, a type changed, a
  route retired, an error code repurposed: that is a `v2`, never a change to `v1`.
- **Additive changes arrive within a version.** A new field, a new route, a new event type, a new
  value of an open enumeration: clients are expected to ignore what they do not know.
- **A version is supported for at least twelve months after its successor is published**, and its
  retirement is announced on every answer before the day: `Deprecation` (RFC 9745, the day it was
  deprecated as `@<unix seconds>`), `Sunset` (RFC 8594, an HTTP date) and `Link: </api/v2>;
  rel="successor-version"`. From the sunset day it answers `410`.
- **A version nobody published is `404 API_VERSION_UNKNOWN`**, with `Link: </api/v1>;
  rel="latest-version"`.

The unversioned `/api/{service}/…` form predates versions and is the deprecated alias of the
current one. Every answer on it — a success or a refusal — carries the three headers:

```
Deprecation: @1790121600
Sunset: Thu, 30 Sep 2027 00:00:00 GMT
Link: </api/v1>; rel="successor-version"
```

On 30 September 2027 it answers `410 API_VERSION_RETIRED`. Move to `/api/v1/…` before then; the
gateway routes the two forms identically, so the change is a prefix.

### The document

`GET /api/versions` needs no credential and says all of it:

```json
{
  "data": {
    "current": "v1",
    "versions": [
      { "version": "v1", "status": "current", "base": "/api/v1/{service}" },
      { "version": "unversioned", "status": "deprecated", "base": "/api/{service}",
        "deprecatedSince": "2026-09-23", "sunset": "2027-09-30", "successor": "/api/v1" }
    ],
    "policy": "Breaking changes ship only as a new version under /api/v1/. …",
    "openapi": "/api/v1/{service}/openapi"
  }
}
```

A version's `status` is `current`, `supported` (an older version still answered), `deprecated` or
`retired`. Every service describes itself in OpenAPI 3.1 at `/api/v1/{service}/openapi`, and the
gateway at `/openapi`.

### Configuration

The gateway reads the policy once at start and stops if it is inconsistent (a current version not
listed, a sunset before the deprecation):

| Key | Default | Meaning |
|---|---|---|
| `storeql.gateway.api.current` | `v1` | the version new integrations are pointed at |
| `storeql.gateway.api.versions` | `v1` | every version still answered, comma-separated |
| `storeql.gateway.api.alias-deprecated-since` | `2026-09-23` | the day the unversioned alias was deprecated |
| `storeql.gateway.api.alias-sunset` | `2027-09-30` | the day it stops |

Publishing a `v2` means: `versions=v1,v2`, `current=v2`, and the announcement of `v1`'s own sunset
on its answers — which is the next piece of work here, since today only the alias carries dates.

## The sandbox

A business's **sandbox** is a second tenant of its own — marked `mode: SANDBOX`, naming the live
business as `sandboxOf` — where an integrator creates products, books stock, places orders, mints
keys and receives webhooks against nothing real:

- **No message leaves it.** notification-svc writes what a sandbox would have sent to its log as
  `SUPPRESSED` — the recipient and the words, for the integrator to read — and sends nothing by
  email, text or push. In-app messages still show.
- **No money moves.** payment-svc uses the MANUAL provider for a sandbox whatever the deployment
  configured, so nothing is authorised or captured.
- **Nothing is billed.** It sits on the `SANDBOX` plan the platform keeps for sandboxes — sold to
  nobody, off the price list, never given to a live business (`409 PLAN_SANDBOX_ONLY`) — with small
  allowances (two stores, five staff, two hundred products, three hundred requests a minute) the
  platform may change like any plan's. It has no subscription.
- **It is real everywhere else.** The same services, the same routes, the same events; a webhook
  registered in the sandbox is delivered, signed, like any other.

The owner makes it (`POST /admin/tenant/sandbox`), reads it (with a manager), removes it (`DELETE`),
one at a time and never from inside one (`409 SANDBOX_NESTED`). The live business's default store is
copied in so stock has somewhere to sit. Removed, it is switched off with the reason
`SANDBOX_DELETED`, every service erases what it held of it (the same `TenantDataErasureDue` a
business leaving the platform gets, without the notice period), and another can be made at once.

Getting in:

- **A token.** `POST /auth/sandbox/token` (the live owner) trades the caller's token for one that
  names the sandbox as its tenant: an owner there, `amr: ["sandbox"]` so the app shows where it is,
  and no refresh token — the sandbox session lasts one access token and is re-entered from the live
  one. The app's admin console shows a banner and a way back.
- **A key.** `POST /auth/admin/api-keys { …, "sandbox": true }` mints a key that starts
  `sqk_test_` (forty-nine characters) and acts in the sandbox alone; from inside the sandbox every
  key minted is one. The live business lists and revokes its sandbox keys beside the live ones,
  marked `sandbox: true`. A sandbox key is refused within seconds of the sandbox being removed;
  a sandbox token already issued lasts out its fifteen minutes, like a suspended business's staff
  tokens, and is neither renewed nor issued again.

Tell the two apart at a glance: a live key starts `sqk_`, a sandbox key `sqk_test_`; a sandbox's
name ends ` (sandbox)`; its profile says `"mode": "SANDBOX"`.
