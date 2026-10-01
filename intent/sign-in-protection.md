# Sign-in protection: unlock, lockouts that lengthen, a person's sessions, recovery, break-glass, enumeration

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue (platform/auth-brute-force-lockout, auth-mfa-enrolment-and-challenge, auth-signin-staff-shopper-platform-admin, plat-platform-admin-overrides-and-breakglass) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, platform and access domain — AUTH-211, AUTH-212, AUTH-112, AUTH-314, PLAT-712 |
| **Services** | the gateway owns the lockout counters (Redis) · iam-svc owns logins, sessions (refresh tokens), recovery codes, the break-glass and decides who may unlock whom · notification-svc words the notices · the app gets the screens |
| **Builds on** | gateway `BruteForceFilter` / `BruteForceProtectionService` (per account and per IP, 5 failures, 15 minutes, `storeql.gateway.brute-force.*`), `RateLimitFilter`, iam-svc `refresh_tokens`, `POST /auth/sessions/revoke-all`, `mfa_recovery_codes` and `MfaService.regenerateRecoveryCodes`, `MfaService.resetFor`, `BootstrapResource` (`POST /bootstrap/admin`, `PLATFORM_ADMIN_TOTP_SECRET`), `GET /auth/admin/security-events`, `PasswordChanged` notice |
| **Built in** | (not yet built) |

## Problem

A genuine person who mistypes a password five times is locked out for fifteen minutes and nobody with authority can lift it: not their owner, not the platform administrator. An attacker who is patient loses nothing: the wait is the same after the tenth lockout as after the first. A person has no way to see where they are signed in, and an owner cannot cut one person's session (only "sign out everywhere", the person's own). The platform administrator is the most powerful login on the platform: it has a second factor from `.env`, and no recovery codes were ever made for that factor; if its phone is lost, its own reset is refused on purpose (`MFA_RESET_SELF`), and if it is the only administrator and locked out, nothing in the platform can bring it back (bootstrap answers `409 BOOTSTRAP_ALREADY_DONE`). Public look-up surfaces (sign-up, single sign-on start) answer differently for a known and an unknown address or business, and only the gateway's general 100 requests a minute limits how fast one machine can ask.

## Outcome

- **A locked-out person is let back in by someone who may.** An owner or a manager held to no store unlocks a login of their own business (the staff list shows "locked, N minutes left" with an Unlock action); the platform administrator unlocks any login and any address; a shopper is unlocked by the platform administrator (a shopper login belongs to no business). Each unlock is in the security trail with who did it. Nobody unlocks themselves or, for the administrator's own login, anyone but another administrator or the break-glass.
- **Repeat lockouts lengthen**, up to a ceiling, and go back to the base after a quiet spell.
- **A person sees their own sessions** (device, when, roughly where, last used) and signs out one; an owner (or an unrestricted manager) sees and ends one member of staff's; the platform administrator anyone's.
- **The platform administrator has recovery codes from the first day**, and the console warns when none are left.
- **If the only administrator is locked out or has lost the factor**, the person holding the deployment's break-glass secret restores access in one documented, audited, self-disabling step; every other administrator and a named security contact are told.
- **Asking "does this address or business exist?" is slow and even.** The look-up surfaces answer the same for known and unknown and are limited per machine much harder than the rest.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the owner and the manager (unlock and end sessions for their staff), the platform administrator (everything), any signed-in person (their own sessions), a locked-out shopper (waits, or is unlocked by support).
- **Channels:** gateway · back-office (staff, security screens) · platform console · every shell's account screen (own sessions).
- **Scope:** per login. Business-wide for the owner and the unrestricted manager (`BusinessWide.require`, as wave 1 set out in `2026-09-30-tenant-svc.md` item 8): a manager held to stores does not unlock or end sessions (`403 BUSINESS_WIDE_ONLY`, `403 STORE_ACCESS_DENIED` for the staff list, as the security-events read does today).
- **Roles that can write:** OWNER, MANAGER held to no store, PLATFORM_ADMIN (and the tiers on [platform-administration](platform-administration.md): an operator unlocks, a support tier only reads). CASHIER, STOREKEEPER, CUSTOMER: `403 FORBIDDEN`.
- **Sandbox tenant:** behaves the same. A sandbox's staff logins are unlocked by their owner like any other.

## Scope

- **In:** cut into slices in build order:
  1. **Escalating lockout (gateway).** Each block a key earns within the repeat window lasts longer than the last, by a factor, up to a ceiling; a quiet repeat window resets the count. New settings, each stating its default and why (see Data and flow). The base (`block-minutes`, 5 failures) is unchanged. Redis keys for accounts become hashed (SHA-256 of the lower-cased address) so an address is no longer a Redis key.
  2. **Unlock (iam-svc decides, gateway state).** `GET` the locked logins the caller may see, and `POST` an unlock. Authority is judged where the identity is (iam-svc), the counters are cleared where they live (a shared key contract, see Data and flow). The audit line names the actor. Unlocking clears the account key and its strikes; the IP key is cleared only by the platform administrator (a shared office address is not a business's to release).
  3. **Sessions.** Give each sign-in a session id (the chain of refresh tokens it rotates through), a short device label, a truncated network prefix and a last-used time; list and end one, own or (with authority) another's. Ending one revokes its chain, ends a POS session it opened, kicks the MQTT push session, and puts the login on the gateway's deny list for the access token's remaining minutes (the mechanism from [platform-administration](platform-administration.md) slice 1; until it ships, an access token already issued lives out its 15 minutes, as after "sign out everywhere" today).
  4. **Administrator recovery codes.** Provisioning the administrator's factor from `.env` also makes ten recovery codes; the first sign-in shows them once and refuses to go on until the person says they are kept (`recoveryCodesShown`); the console shows how many are left and refuses nothing when none are, but says so on every page until new ones are made (`POST /auth/mfa/recovery-codes`, password required, as today).
  5. **Break-glass.** A deployment secret that restores the only administrator, once, audited. Design below; the runbook goes in `docs/BREAK-GLASS.md` and is rehearsed by a drill script the way `scripts/backup-drill.sh` proves restore.
  6. **Look-up limits.** A separate, stricter per-IP class (`LOOKUP` in the `AbuseCounters` framework that [storefront-trust](storefront-trust.md) slice 2 builds; build that first, or build the class with the framework) for the surfaces that answer "does X exist" (sign-up, single sign-on start, and any public look-up added later), and the answers made even. Not to be confused with the **staff customer-lookup limit** of [customer-identity](customer-identity.md) slice 3, which limits a signed-in member of staff by person and lives in customer-svc.
- **Out, on purpose:**
  - **Unlocking by the locked person themselves through email** (a "unlock link"). The password reset is already the self-service path for someone who forgot their password; an unlock link would let an attacker who knows the address end the lockout that protects it.
  - **A third-party CAPTCHA.** It needs a vendor agreement and a per-country decision. The vendor-free proof-of-work `HumanCheck` on the register, reset and payment routes is [storefront-trust](storefront-trust.md)'s slice 3; a vendor is a driver behind that interface if a business ever wants one. The lengthening lockout and the look-up limits here do their own job.
  - **Geo-IP and device reputation on the session list.** The list shows what the platform itself observed (device label, network prefix); a location service is a separate external driver.
  - **Named administrator "break-glass accounts" kept in the database.** A standing extra account is the thing an attacker looks for; the secret lives outside the platform.
  - **Session limits per person** (at most N devices). A business setting nobody asked for.

## Data and flow

- **Owned by the gateway:** the counters in Redis, no table. Keys today: `bruteforce:fail:<key>`, `bruteforce:block:<key>`. New: `bruteforce:strikes:<key>` (how many blocks within the repeat window, expiring with it). The key format and the address hashing sit in one small class in `shared/common-service` (`LockoutKeys`) that both the gateway and iam-svc use, so the contract is written once; iam-svc gets a Redis connection (`storeql.redis.*`, same instance the gateway uses) for lockout keys only. Redis down: the unlock answers `503 AUTH_LOCKOUT_STORE_UNAVAILABLE` (the gateway itself still fails open on sign-in, as its test `BruteForceProtectionServiceFailOpenTest` pins).
- **Owned by iam-svc:**
  - `refresh_tokens` gains `session_id` (uuid, the chain: the first token's own id, carried across rotations), `device_label` (text, up to 80 characters, "Chrome on Windows", built from the user agent and never the raw header), `network` (text, the address truncated to a /24 for IPv4 and /48 for IPv6, so it is not an exact address), `last_used_at`, `auth_method` (PASSWORD, SSO, MFA, PASSKEY). Existing rows get `session_id = id`, the rest null ("Unknown device"). The gateway passes the client network and the user agent to iam-svc on `/auth/login`, `/auth/refresh` and `/auth/sso/token` in headers it sets itself (`X-Client-Network`, `X-Client-Agent`; a client-sent copy is stripped), using its existing `ClientIp` resolution.
  - `break_glass_uses` (append-only): id, at, the administrator restored, the network, the outcome. The secret is never stored here: only its hash is in the deployment's environment.
  - Audit codes in the existing append-only `audit_log`: `LOGIN_UNLOCKED`, `SESSION_REVOKED`, `SESSION_REVOKED_BY_ADMIN`, `BREAK_GLASS_USED`, `BREAK_GLASS_REFUSED`, `RECOVERY_CODES_SHOWN`. `GET /auth/admin/security-events` already reads them (wave 1); the new codes are added to `SecurityEvent`'s allow-list of text that may be shown (actor id, session id; never the secret).
- **Endpoints (all under `/auth`, all behind the gateway's token check):**
  - `GET /auth/admin/lockouts` (OWNER, unrestricted MANAGER, PLATFORM_ADMIN): logins currently blocked, `{userId, email, blockedForSeconds, strikes}`; an owner sees their own business's staff only; another business's login is never listed. Cursor paging.
  - `POST /auth/admin/staff-users/{userId}/unlock` (same roles; `Idempotency-Key`): `404 USER_NOT_FOUND` for a login of another business (as `MfaService.resetFor` does), `409 LOGIN_NOT_LOCKED`, `403 UNLOCK_SELF_REFUSED`, `403 UNLOCK_ADMINISTRATOR_REFUSED` (only another administrator, or the break-glass, unlocks an administrator). `POST /auth/admin/lockouts/unlock {address}` (PLATFORM_ADMIN) for a shopper or an address with no login; `DELETE /auth/admin/lockouts/network/{prefix}` (PLATFORM_ADMIN) for a network.
  - `GET /auth/sessions` (own): `{id, deviceLabel, network, startedAt, lastUsedAt, authMethod, current}`; `DELETE /auth/sessions/{id}` (own; ending the current one is a sign-out); `GET /auth/admin/staff-users/{userId}/sessions` and `DELETE /auth/admin/staff-users/{userId}/sessions/{id}` (OWNER, unrestricted MANAGER, PLATFORM_ADMIN). `404 SESSION_NOT_FOUND` for another login's or another business's.
  - `POST /bootstrap/break-glass` (public path, headers `X-Break-Glass: <secret>`, body `{email}`): see below.
- **Break-glass.**
  - It exists only where the deployment sets `PLATFORM_BREAK_GLASS_HASH` (SHA-256 of a random secret of at least 256 bits, generated with a documented one-liner, the secret itself printed once and kept sealed by a person outside the platform, for instance in the company's password vault; both in git-ignored `.env`, placeholders in `.env.example`, never in the repo) **and** `PLATFORM_BREAK_GLASS_ENABLED=true`, which the person with access to the deployment flips on for the incident and off after.
  - A correct secret and the address of an existing administrator: clears that login's lockout and strikes, deletes its second factors and recovery codes (so its next sign-in answers `mfaEnrolmentRequired` and it sets a new factor, exactly as a first sign-in does), revokes all its refresh tokens, writes `BREAK_GLASS_USED`, and **disables itself** until a new hash is deployed. It never sets a password and never makes an administrator: it restores one that exists. A wrong secret: `403 BREAK_GLASS_REFUSED`, the same answer for a wrong address and a wrong secret, counted against the network by the look-up limits; three wrong in an hour block the network for the hour (a fixed rule of the endpoint, not a business setting) and are audited.
  - Every use sends a `BreakGlassUsed` notice (email, the platform's own words, never a template a business can reword) to every other administrator and to `PLATFORM_SECURITY_CONTACT` (a deployment address in `.env`), so a use nobody expected is seen at once.
  - If the administrator's factor is fine and the **password** is lost, the ordinary password reset applies (an administrator is exempt from it today, by `intent/password-reset.md`; that stays: break-glass is the path, and it clears the factor so a stolen mailbox alone can never take the platform).
  - If **the signing key material** is lost, the runbook covers it and no endpoint does: `signing_keys` are sealed under `storeql.jwt.secret`; restore from the backup ([docs/BACKUP-AND-RESTORE.md](../docs/BACKUP-AND-RESTORE.md)) or, if the sealing secret is gone too, deploy a new one and run key rotation on empty (`SigningKeyResource`): every session dies and every login signs in again. The drill script rehearses this against the running stack.
- **Events published:** `LoginUnlocked` (`storeql.iam.login-unlocked`; `userId`, `tenantId` or null, `by`; consumed by notification-svc, which tells the person "your sign-in was unlocked by your administrator" in the platform's words, by email, so an unlock a person did not ask for is seen) and `BreakGlassUsed` (`storeql.iam.break-glass-used`, no tenant; consumed by notification-svc to the administrators and the security contact). Both through the outbox with an `eventId`; the consumers dedupe on it.
- **Retryable writes (Idempotency-Key):** the unlock POSTs, session deletes are naturally idempotent (a second is `404`), break-glass is single-use by design.
- **New error codes:** `LOGIN_NOT_LOCKED` 409, `UNLOCK_SELF_REFUSED` 403, `UNLOCK_ADMINISTRATOR_REFUSED` 403, `SESSION_NOT_FOUND` 404, `BREAK_GLASS_REFUSED` 403, `BREAK_GLASS_DISABLED` 404 (the route answers as if it did not exist when the deployment has not enabled it), `AUTH_LOCKOUT_STORE_UNAVAILABLE` 503, `AUTH_LOOKUP_RATE_LIMITED` 429 (with `Retry-After`).
- **Settings (owner: the gateway unless stated; defaults are the platform's own security posture, so a default is set, and each is justified):**
  - `storeql.gateway.brute-force.escalation-factor` = **2**. Doubling is the common practice (OWASP guidance on throttling): the second block is 30 minutes, then 60, so a patient guesser is slowed geometrically while a person who slips twice waits no longer than an hour. `1` turns escalation off.
  - `storeql.gateway.brute-force.max-block-minutes` = **1440** (24 hours). Above a day a lockout becomes a denial of service against the victim, which an attacker can cause on purpose; a day is the longest that is still "wait, or ask your administrator".
  - `storeql.gateway.brute-force.repeat-window-hours` = **24**. Strikes count blocks earned within a day and are forgotten after a day of quiet; long enough to catch a slow attacker returning each morning, short enough that a person who was locked out last month starts fresh.
  - `storeql.gateway.rate-limit.lookup-per-minute` = **20** per network for the look-up class. A person typing their own address, or a business name, makes a handful of attempts a minute; twenty leaves room for a shared office network and cuts a scan of a directory to a crawl (the general limit of 100 stays for everything else).
  - `storeql.iam.break-glass.wrong-attempts-per-hour` is **not a setting**: three, fixed, in the endpoint (see above). A break-glass secret has 256 bits; the limit is to make guessing visible, not to protect against it.
  - The base 5 failures and 15 minutes stay as they are (`max-failures`, `block-minutes`); nothing is loosened.

## Money, time and limits

- **Currency:** none. **Ledger postings:** none.
- **Dates:** every instant UTC; the console and the app show them in the viewer's zone. "Blocked for N seconds" is computed at read time from the Redis time-to-live.
- **Plan limits:** none. Locks and sessions are security, not a metered allowance.

## Constraints

- **Golden rules:** 3 (the business of an unlocked login is the login's own, never the request's: a target in another business is `404`); 5 (the break-glass hash and the security contact are deployment values, sealed, never in the repo); 8 (`audit_log` and `break_glass_uses` stay append-only); 12 (Redis down does not block sign-in; unlock says so).
- **Personal data:** the network is a truncated prefix, the device label a short derived string; both are shown to the person they belong to and to those who may end the session, and are deleted with the refresh token when it is purged (the existing retention of expired tokens) or the login is deleted.
- **RS256 and token conventions unchanged** (CLAUDE.md tokens paragraph): nothing here hands a service a signing secret; the gateway deny list carries user ids and expiry only.
- **Second factors (20.12):** a wrong second-factor answer still counts against the lockout, and an unlock clears it. The administrator always has a factor: break-glass removes it only to make the next sign-in enrol a new one, and `PLATFORM_ADMIN_TOTP_SECRET` is not read again after bootstrap.
- **Existing tenants:** nothing changes until a lockout repeats; sessions of tokens issued before the migration show as "Unknown device" and can still be ended.

## Open questions

- [x] Who may unlock? Recommended: the owner and the unrestricted manager for their own staff, the platform administrator for anyone; never one's own; an administrator only by another or by break-glass. → **as recommended** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] How long may a lockout grow, and how? → **doubling, capped at 24 hours, forgotten after 24 quiet hours** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What of a session may be shown? → **a device label and a truncated network, never an exact address or the raw user agent** (industry standard: data minimisation, under the user's standing instruction of 2026-09-30)
- [x] Where does the unlock live, given the state is in the gateway? → **authority in iam-svc, counters cleared through the shared key contract in Redis** (industry standard: decide where the identity is, act where the state is, under the user's standing instruction of 2026-09-30)
- [x] What if the only administrator is locked out? → **break-glass with a sealed deployment secret, off unless the deployment switches it on, single-use, audited, notifying the other administrators and a security contact** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] What does a session list do about immediate effect? → **ends the refresh chain at once and denies the access token through the gateway deny list; without the deny list an access token lives its remaining minutes** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] The fifth failure blocks; the next block is twice as long, then four times, and never longer than the ceiling — pure `EscalationTest`, gateway `BruteForceFilterLockoutTest` extended
- [ ] A quiet repeat window resets the lengthening; a successful sign-in still clears the failures but not the strikes inside the window — `BruteForceFilterLockoutTest.strikesSurviveASuccessAndForgetAfterTheWindow`
- [ ] An owner unlocks a locked staff login of their own business and the person can sign in at once; an audit line names the owner — iam-svc `LockoutIT.anOwnerUnlocksTheirStaff`, `SecurityEventIT.unlockIsInTheTrail`
- [ ] Another business's owner, a manager held to a store, a cashier, a storekeeper and a shopper cannot unlock (404 for another business's login, 403 otherwise) and nothing changes — `LockoutIT.nobodyElseUnlocks` (tenant-isolation line: they name our user id and even our store id)
- [ ] Nobody unlocks themselves (`403 UNLOCK_SELF_REFUSED`); an administrator is unlocked only by another administrator (`403 UNLOCK_ADMINISTRATOR_REFUSED` for an owner naming one) — `LockoutIT.selfAndAdministratorRules`
- [ ] Unlocking a login that is not locked is `409 LOGIN_NOT_LOCKED`; a replay with the same `Idempotency-Key` answers the first result — `LockoutIT.notLockedAndReplay`
- [ ] The person is told by email their sign-in was unlocked, once per event — notification-svc `LoginUnlockedHandlerTest`, `NotificationIT.unlockNoticeOncePerEvent`
- [x] The list of sessions shows the caller's own only; ending one revokes that chain and no other; ending it twice is `404` — iam-svc `SessionsIT.ownListAndEndOne` [iam half built as `SessionsIT.ownListAndEndOne` and `.otherLoginsSessionsAreNeverVisibleOrEndable`; the owner-ends-staff and POS/deny-list halves are not built]
- [ ] An owner lists and ends one member of staff's sessions; another business's owner and a store-held manager are refused; a shopper's sessions are never visible to a business — `SessionsIT.ownerEndsAStaffSession`, `.otherBusinessSeesNone`
- [ ] Ending a session ends the POS session it opened and refuses that access token at the gateway — `SessionsIT.endingOneEndsItsPosSession`, gateway `DenyListFilterTest` (after platform-administration slice 1)
- [ ] The device label and network are derived, truncated, never the raw header or address; a client-sent `X-Client-Network` is stripped — pure `SessionLabelTest`, gateway `ClientHeadersTest`
- [ ] Bootstrap with a factor from `.env` makes ten recovery codes, shown once; a recovery code signs the administrator in once and is spent — `MfaIT.theAdministratorHasRecoveryCodesFromDayOne`
- [ ] Break-glass with the right secret restores the only administrator (factor cleared, lockout cleared, sessions revoked, next sign-in enrols a new factor), disables itself, audits, and notifies the others and the contact — `BreakGlassIT.restoresTheOnlyAdministratorOnce`
- [ ] Break-glass is `404` where not enabled; a wrong secret and a wrong address answer identically; three wrong in an hour block the network — `BreakGlassIT.disabledWrongSecretAndBlock`
- [ ] The drill rehearses the runbook against the running stack, including the lost sealing secret — `scripts/break-glass-drill.sh` (CI form `scripts/break-glass-selftest.sh`, as `backup-selftest.sh`)
- [ ] Sign-up and single-sign-on start answer alike for a known and an unknown address or business; the twenty-first ask in a minute from one network is `429 AUTH_LOOKUP_RATE_LIMITED` — `LookupRateLimitIT`, gateway `RateLimitFilterLookupTest`
- [ ] Flow guard: the sign-in lockout in `flow-guard-comprehensive` still passes with the base window; k6 `lockout-flow` covers unlock and escalation

## Screens

- **Back-office → Staff:** a "Locked" badge with the time left and an **Unlock** action (confirm, then a snackbar), and a **Sessions** drawer per person listing device, network, last used, with **Sign out** on each; both hidden from managers held to stores.
- **Account (every shell):** "Where you are signed in": the list, the current one marked, **Sign out** on each, and the existing **Sign out everywhere**. Sends `DELETE /auth/sessions/{id}`.
- **Platform console → Administrators:** recovery codes left (with **Make new codes**, password prompt), the administrator count, and a banner when there is one. **Sign-in security:** locked logins across the platform with **Unlock**, the network list with **Release**.
- **Sign-in:** a lockout says how long the wait is (the `Retry-After` the gateway already sends) and that an administrator can unlock it.
- Words not codes (`status_labels.dart`), dates through `AppFormat.dateTime`, adaptive per UI-GUIDE §7.2.

## Decisions

Filled while building. Known ahead of time (the exception is stated once here, for every page that touches it): a shared `LockoutKeys` class in `common-service` is a deliberate exception to "no shared state between services" because the counters are the gateway's and iam-svc only clears them; it is one class, tested from both sides.

- **Decision (slice 3, iam half built):** a session is the `session_id` shared by a refresh chain (V20 `refresh_tokens.session_id/started_at/device_label/network`); the access token carries it as `sid`; last used is the newest token's issue time (a renewal, not each call). `current` is marked from an optional `X-Session-Id` header, which the app reads from its own `sid` until the gateway forwards it. Labels only: device from User-Agent, network truncated to /24 or /48 from `X-Forwarded-For`. Not built: ending a session's POS session, the deny list, the admin list/end of staff sessions.
