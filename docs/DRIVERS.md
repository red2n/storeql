# Drivers

How the platform reaches anyone outside it. A card processor, a carrier, a payee check, a human check, a webhook receiver and an SMS gateway are each a **driver** behind an interface; the business chooses which one it uses, and the platform proves each against a stub of that party's API. The pattern is the one [accounting connectors](ACCOUNTING-CONNECTORS.md) set: purchase-svc's `client.accounting`.

## The rule

1. **The interface is the seam.** `service/` knows only the driver interface (`AccountingPackage`, `PaymentProvider`, …) and the value types and exceptions that travel with it. The driver classes are in `client/` and are the only code that knows a party's URL, headers, signing or JSON. No HTTP client type is used outside `..client..`. Both are ArchUnit rules in common-test's `StoreQlArchRules`: `DRIVERS_STAY_BEHIND_THE_INTERFACE` and `serviceUsesOnlyTheDriverInterface("..client.<x>..")`; a service adds them to its `ArchitectureTest`.
2. **A catalogue row per driver.** The drivers a business may choose from are rows in a `CATALOGUE` (as `Accounting.CATALOGUE`), each with the settings it needs; the table that holds the choice carries `ck_<x>_provider` listing exactly those names. Adding a driver means a driver, its stub-backed test, a catalogue row and the check, together.
3. **Credentials are sealed.** Tokens and keys the business gives are sealed with common-service `SealedSecrets` under `storeql.<x>.secrets-key` (16, 24 or 32 bytes, base64; `openssl rand -base64 32`), read from `.env` (git-ignored) with an empty placeholder in `.env.example`, never shown again, never in a log, an export or an event. With no key, choosing a real driver is refused (`503 <X>_NOT_CONFIGURED`) and `SIMULATED` still works.
4. **`SIMULATED` is a driver.** It answers in-process, needs no credentials, can be told to refuse so a refusal and its retry can be rehearsed, and is what tests and a business's sandbox tenant use (`TenantProfiles.Profile.sandbox()`, never a flag on a request). Nothing leaves the platform from it.
5. **One key per call.** A call that changes something on the other side carries an idempotency key derived from our own id (`Ids.derived(id, "step")`); a party that offers none makes a possibly-landed call `UNCERTAIN`, left for a person, never for the clock.
6. **Amounts** are sent as the party wants them (minor units for most card and payment APIs, by the currency's own fraction digits, never an assumed two). No country, currency or language is assumed in a driver.
7. **A stub-backed test for every real driver**, proving the exact request: URL, method, headers, body (JSON or form), amounts, the key, and any signature. Answers a party gives when it refuses, throttles, times out or sends nonsense are tested too.

## The test kit

`shared/common-test` gives the stub, so no driver test builds its own server:

- `DriverStub.start(request -> DriverStub.Reply.json(200, "{…}"))` (or `startTls`) answers what the test programs and records every request whole: `method()`, `route()`, `query()`, `header(name)`, `body()`, `form()`, the raw `bodyBytes()`. `assertOneIdempotencyKeyPerCall(header)`, `DriverStub.minorUnits(amount, currencyCode)` and `assertMinorUnits(sent, amount, currencyCode)` check the last two points above.
- `Hmac` computes and checks HMAC-SHA256 (hex or base64) over the raw body; `Recorded.hmacHexMatches(header, key, prefix)` and `hmacHexMatchesWithTimestamp(…)` check what a stubbed receiver got. The bytes signed are the bytes sent.
- `startTls` makes a throwaway certificate at test time with the JDK's `keytool` (in memory, files deleted at once, nothing committed); `clientSslContext()` trusts it and only it.

## The drivers

| Driver | Chooses among | Designed in |
|---|---|---|
| `AccountingPackage` | Xero, QuickBooks Online, Sage, `SIMULATED` | [ACCOUNTING-CONNECTORS.md](ACCOUNTING-CONNECTORS.md) (built) |
| `SmsProvider` | the SMS gateways notification-svc knows, `SIMULATED` | built |
| `PaymentProvider` | card processors, `MANUAL`, `SIMULATED` | [card-payments](../intent/card-payments.md) |
| `Carrier` | shipping carriers, `SIMULATED` | [shopper-returns](../intent/shopper-returns.md), [proof-of-handover](../intent/proof-of-handover.md) |
| `PayeeVerifier` | bank-account name checks, `SIMULATED` | [supplier-assurance](../intent/supplier-assurance.md) |
| `HumanCheck` | bot-check providers, `SIMULATED` | [storefront-trust](../intent/storefront-trust.md) |
| `WebhookTransport` | HTTPS delivery, `SIMULATED` | [webhook-delivery-controls](../intent/webhook-delivery-controls.md) |

## Going live

A driver is proved against a stub, never against the real party in CI. What a business needs to go live, and what the connection screen asks for, is the party's own: an account with it, the credentials it issues (client id and secret, an API key, a signing secret), the settings its API names (an account or realm id, a sandbox or production choice), and, where the party requires it, its agreement to the terms. The connection is tried once (the provider's own read call) before it is switched on, and a party that stops accepting the credentials stops the driver with the party's words on the connection's `lastError`, needing a person.
