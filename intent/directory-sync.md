# Directory sync: SCIM 2.0 provisioning for a business that signs in through its own identity provider

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, from the StoreQL Flow Tests catalogue (platform/auth-sso-business-identity-provider, AUTH-413) · 2026-09-30 |
| **Roadmap** | new: the flow catalogue, platform and access domain — AUTH-413, "no SCIM / group-to-role auto-provisioning" |
| **Services** | iam-svc owns the SCIM endpoint, the directory tokens and the logins · tenant-svc owns group-to-role mappings and the staff assignments they produce · notification-svc tells the owner of failures · the gateway lets a directory token reach only the SCIM routes · the app gets the owner's Directory screen |
| **Builds on** | iam-svc single sign-on (`sso_connections`, `sso_identities`, `SsoService`, `required_tiers`), `users.status` (ACTIVE / DISABLED), `POST /auth/admin/staff-users`, `refresh_tokens`, tenant-svc `staff_assignments` (now with business-wide rows, `V1__init.sql` (folded)), `StaffAssigned` / `StaffRemoved`, `RoleGrants` (wave 1), `GET /admin/tenant/audit`, `intent/password-reset.md` (a tier that signs in through its own provider is told so, with no link) |
| **Built in** | (not yet built) |

## Problem

A business that connects its own identity provider (Entra ID, Okta, Google Workspace, Keycloak) still adds every member of staff by hand: a person joins the company and someone must create their login and assign a role at each store; a person leaves the company and their StoreQL login keeps working until someone remembers. The provider already knows who is in which group. Single sign-on today only authenticates a person who is already here (or links the first time by email).

## Outcome

- The owner turns on **Directory sync**, gets a base URL and a token to paste into the provider, and maps each provider group to a role and the stores it applies to.
- When the provider adds a person to the group "Store 12 Cashiers", they have a login and that role at store 12 without anyone touching StoreQL. When they leave the group, the role goes. When they leave the company, they can no longer sign in, in seconds, and every session they had is ended.
- Anything an owner or manager assigned by hand is never touched by the directory.
- The directory can never make an owner, and never removes the last one.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the owner (turns it on, maps groups), the business's IT administrator using their provider (the caller of SCIM, a machine), the manager (sees who is directory-managed).
- **Channels:** the SCIM endpoint (machine to machine, through the gateway) · back-office Directory screen.
- **Scope:** per business. One directory token per business (two live at once while it is being replaced). A group maps to a store list or the whole business (the business-wide assignment tenant-svc allows since `V1__init.sql`).
- **Roles that can write:** OWNER only (token, mappings, turning on and off): directory sync is a way of granting access, so it is the owner's, like the provider itself (`AccountSecurityIT.ssoRolesAndIsolation`). A manager reads. The SCIM caller acts with a token, not a role.
- **Sandbox tenant:** works the same, against the sandbox business's own provider; the token has the `sqk_test_` style prefix for sandbox.

## Scope

- **In:**
  1. **The token and the service description (iam-svc, gateway).** The owner mints a directory token (`sqk_scim_…`, shown once, only its hash kept, revocable, with `last_used_at`); the gateway lets a bearer that starts `sqk_scim_` through to `/scim/v2/**` only (the same prefix check `ApiKeyIntrospector` uses for `sqk_` keys; a directory token on any other path is `401`), and `ServiceProviderConfig`, `ResourceTypes` and `Schemas` answer (RFC 7644 §4) declaring what is supported: PATCH yes, bulk no, filter yes (`userName eq`, `externalId eq`, `displayName eq`) with a maximum results of 100, change-password no, sort no, ETag no. **A directory user has no password**: SCIM never sets one, and directory sync requires an enabled SSO connection (`409 DIRECTORY_SSO_REQUIRED`), so a provisioned person signs in through the provider only.
  2. **Users (iam-svc).** `GET /Users` (filter, `startIndex`/`count` as RFC 7644 §3.4.2.4), `POST /Users`, `GET/PUT/PATCH/DELETE /Users/{id}`. `userName` is the login's email, `externalId` the provider's own key, `active` true/false, `name`, `emails`, `phoneNumbers` (read in the business's own countries by `PhoneNumbers`, never a prefix assumed). A user that already exists in this business by email (a login someone added by hand) is **adopted**, not duplicated, when the provider says it verified the address (`require_verified_email` of the connection applies): the response is `409` with the `uniqueness` scimType otherwise. Creating a user creates a STAFF login with no assignment yet (assignment comes from group mappings, slice 4).
  3. **Deprovisioning (iam-svc, gateway, tenant-svc).** `active: false`, or `DELETE`, disables the login (`users.status = DISABLED`, a new `disabled_by = DIRECTORY` marker so a person who is disabled for another reason is not re-enabled by the directory), refuses sign-in and refresh from that moment, revokes every refresh token, ends its POS sessions, kicks its MQTT push session and puts it on the gateway's deny list for the access token's remaining minutes (the deny list from [platform-administration](platform-administration.md) slice 1; the two pages share it). `DirectoryUserDeprovisioned` tells tenant-svc, which removes the assignments whose source is DIRECTORY. `active: true` again re-enables and re-applies the mappings. A SCIM `DELETE` is a disable, not an erase: the login and its history stay (orders, audit lines and time entries name it); erasing a person is the privacy path, not the directory's. SCIM's own idempotency holds: a second delete answers `404`, a second `active:false` `200`.
  4. **Groups and mappings (iam-svc receives, tenant-svc decides).** `GET/POST/PUT/PATCH/DELETE /Groups` with members (RFC 7644 §3.5.2, `add`/`remove`/`replace` on `members`). iam-svc keeps `directory_groups` (the provider's id, name, members) and publishes `DirectoryGroupChanged` with the whole membership (a snapshot per group, not a delta, like `CrossDockAllocationsSet`). tenant-svc owns `directory_group_mappings` (group id, role, store ids or business-wide, who set it) managed by the owner, and reconciles: the assignments wanted = mapping × membership; it adds the missing and removes the surplus **only among assignments it made** (`staff_assignments.source = 'DIRECTORY'`, a new column, existing rows `MANUAL`). Reconciling is idempotent and order-independent. A group with no mapping is stored and does nothing.
  5. **The owner's Directory screen and the failures they need to see (tenant-svc, notification-svc, app).** Turn on/off, the token, mappings, the people the directory manages and their last change, and a list of what could not be applied and why. A refusal never fails the provider's call (see below); it lands in `directory_sync_issues` and the owner is told once per issue kind per day.
  6. **The protocol test kit and provider quirks.** A stub SCIM client in iam-svc's tests that replays the RFC 7643 §8 and RFC 7644 §3 example messages verbatim, then the known quirks of Entra ID (PATCH `active` as the string `"False"`, `Operations` op names in capitals, `path` filters like `members[value eq "…"]`, a `userName eq` probe before every create) and Okta (PUT for updates, `emails` with `primary`).
- **Out, on purpose:**
  - **Making or removing an owner through the directory.** A mapping to the OWNER tier is refused when saved (`403 DIRECTORY_OWNER_TIER_NOT_MAPPABLE`), consistent with single sign-on never requiring the tier for owners and with wave 1's rule that only an owner makes an owner (`STAFF_OWNER_TIER_OWNER_ONLY`). Deprovisioning a login that is an owner answers SCIM `403` (`mutability`) and is raised on the owner's screen: the last owner leaving is a decision for a person.
  - **Custom roles that hold `staff.manage`** through a mapping: refused like the owner tier (`ROLE_STAFF_MANAGE_OWNER_ONLY` is an owner's to give directly; a machine does not give it). `RoleGrants` applies to every directory-made assignment as if the owner had made it.
  - **Provisioning shoppers.** SCIM here is for staff. A shopper login belongs to no business.
  - **SCIM `Bulk`, `/Me`, sort, ETags, password change.** Declared unsupported in `ServiceProviderConfig`; clients that need them are told.
  - **Inbound sync from StoreQL to the provider** (write-back). One direction.
  - **Just-in-time role assignment at first sign-in.** Assignment comes from group membership through the mapping, one path; JIT would be a second.
  - **Directory sync for a business that does not use single sign-on.** It would create logins with no way to sign in.
- **Conflict with a convention, to be settled by building:** SCIM clients require `application/scim+json` and the SCIM error body (`urn:ietf:params:scim:api:messages:2.0:Error`: `schemas`, `status`, `scimType`, `detail`), which is not RFC 9457. `ProblemResponseFilter` (common-web) must leave `/scim/v2/**` alone and the SCIM resource writes its own errors (`invalidFilter`, `uniqueness`, `mutability`, `invalidValue`, `noTarget`, `invalidSyntax`, `tooMany`). Recorded as an exception in CLAUDE.md's error convention when built.

## Data and flow

- **Owned by iam-svc:**
  - `scim_tokens`: id, tenant, prefix, hash, created by/at, last used at, revoked at/by. At most two live per business.
  - `directory_settings`: one row per business: enabled, updated by/at.
  - `users` gains `external_id` (text, unique per business when set) and `disabled_by` (null, DIRECTORY, or ADMIN); `users.status` already holds DISABLED.
  - `directory_groups` (id, tenant, external id, display name, updated at) and `directory_group_members` (group, user).
  - Audit codes in `audit_log`: `DIRECTORY_USER_PROVISIONED`, `DIRECTORY_USER_ADOPTED`, `DIRECTORY_USER_DEPROVISIONED`, `DIRECTORY_USER_REACTIVATED`, `DIRECTORY_TOKEN_MINTED`, `DIRECTORY_TOKEN_REVOKED`.
- **Owned by tenant-svc:** `directory_group_mappings` (tenant, group id, role, store ids, business-wide flag, created by/at; unique on group + role + target); `staff_assignments.source` (MANUAL, DIRECTORY); `directory_sync_issues` (append-only: tenant, kind, subject, detail, at). Every mapping change and every directory-made assignment is also in the admin change log (`GET /admin/tenant/audit`: new types `DIRECTORY_MAPPING_SET`, `DIRECTORY_MAPPING_REMOVED`, and `STAFF_ASSIGNED` with the actor `directory`).
- **Needs from other services:** tenant-svc reads a group's membership from the event, never iam-svc's tables; plan limits (`staff.max`) are judged by tenant-svc when it applies an assignment (it owns staff counts): over the limit, the assignment is not made, an issue `DIRECTORY_STAFF_LIMIT` is recorded and the owner told; the login still exists and the provider's call still succeeds.
- **Endpoints (SCIM, iam-svc, behind the gateway):** `/scim/v2/ServiceProviderConfig`, `/ResourceTypes`, `/Schemas`, `/Users`, `/Users/{id}`, `/Groups`, `/Groups/{id}` as above, with a token. **Owner endpoints:** `GET/PUT /auth/admin/directory` (turn on/off; `DIRECTORY_SSO_REQUIRED`), `POST /auth/admin/directory/tokens`, `GET` the tokens (prefix, last used), `DELETE /auth/admin/directory/tokens/{id}` (iam-svc); `GET/PUT/DELETE /admin/tenant/directory/mappings`, `GET /admin/tenant/directory/managed` and `GET /admin/tenant/directory/issues` (tenant-svc). Roles: owner writes, unrestricted manager reads.
- **Events published (outbox, `eventId`, idempotent consumers):**
  - `DirectoryGroupChanged` (`storeql.iam.directory-group-changed`; tenant, group id, name, the full member user ids): tenant-svc reconciles the assignments once per event.
  - `DirectoryUserDeprovisioned` / `DirectoryUserReactivated` (`storeql.iam.directory-user-…`; tenant, user id): tenant-svc removes or re-applies the DIRECTORY assignments; inventory-svc, order-svc and the others learn through the existing `StaffRemoved`, which tenant-svc publishes for each assignment it removes, as for a manual removal. **`StaffAssigned` and `StaffRemoved` (wave 1 already added `businessWide: true`, with `storeId` omitted, for a head-office manager) gain one more additive field, `source` (`MANUAL` or `DIRECTORY`; absent = `MANUAL`).** Every consumer keeps reading `businessWide` as "all stores"; a directory mapping to the whole business is the MANAGER tier only, as wave 1 fixed, and the approvers and alert-recipient queries of [approvals](approvals.md) and [exception-alerts](exception-alerts.md) count a business-wide manager for every store.
  - `DirectorySyncIssueRaised` (tenant-svc, `storeql.tenant.directory-sync-issue-raised`): notification-svc tells the owner (in-app and email, the platform's words), one per issue kind per day.
- **Retryable writes:** SCIM is idempotent by its own rules (a create with an existing `userName` is `409 uniqueness`, a delete twice `404`); the owner's token mint takes an `Idempotency-Key`.
- **New error codes:** `DIRECTORY_SSO_REQUIRED` 409, `DIRECTORY_NOT_ENABLED` 403 on SCIM calls when off (SCIM error body), `DIRECTORY_OWNER_TIER_NOT_MAPPABLE` 403, `DIRECTORY_MAPPING_INVALID` 400 (unknown role, a store of another business), `DIRECTORY_TOKEN_LIMIT` 409 (two live).

## Money, time and limits

- **Currency, ledger:** none.
- **Dates:** UTC; SCIM `meta.created`/`lastModified` in RFC 3339 UTC.
- **Plan limits:** `staff.max` is enforced by tenant-svc when the assignment is applied (above). A plan without directory sync: **not gated**; no entitlement key is added (single sign-on is not gated either), so `Plans.CATALOGUE` is unchanged.
- **Rate:** SCIM calls count against the business's `requests.per-minute` like any API request (the gateway's `TenantRateLimitFilter`); a provider that syncs thousands of users is told `429` with `Retry-After`, which SCIM clients honour.

## Constraints

- **Golden rules:** 1 (tenant-svc never reads iam-svc's tables: it learns membership from events; iam-svc never writes assignments); 3 (the tenant is the token's business, never a body field); 6 and 7 (outbox, and a redelivered `DirectoryGroupChanged` changes nothing); 8 (issues and audit stay append-only); 15 (every SCIM body validated against its schema, `invalidSyntax`/`invalidValue` otherwise).
- **Token conventions:** the directory token is a random 256-bit secret shown once, stored as a hash, never logged; a service is never handed a signing secret; nothing here verifies JWTs differently.
- **Manual wins:** a person assigned by hand keeps that assignment when the directory drops them from a group; the directory removes only what it made. A person who is both keeps the manual one.
- **Existing tenants:** off until an owner turns it on; nothing changes for a business that does not.
- **Location-neutral:** phone numbers are read through `PhoneNumbers` in the business's own countries; no prefix, language or zone assumed; SCIM `locale` and `timezone` attributes are stored if given and never defaulted.

## Open questions

- [x] Who may turn it on and map groups? → **the owner only; a manager reads** (industry standard: granting access is the owner's, under the user's standing instruction of 2026-09-30)
- [x] Can the directory grant the owner tier? → **never; refused when the mapping is saved** (industry standard: least privilege, under the user's standing instruction of 2026-09-30)
- [x] Does a directory user have a password? → **no; sign-in is through the provider only, and directory sync requires single sign-on** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Does a SCIM delete erase the person? → **it disables; erasure is the privacy path** (industry standard: history stays attributable, under the user's standing instruction of 2026-09-30)
- [x] What if the plan's staff allowance is full? → **the login is made, the assignment is not, and the owner is told; the provider's call still succeeds** (industry standard: a provisioning protocol should not fail on a business rule, under the user's standing instruction of 2026-09-30)
- [x] How fast is deprovisioning? → **refresh and sign-in refused at once, access token denied at the gateway at once, otherwise it lives its 15 minutes** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

- [ ] `ServiceProviderConfig`, `ResourceTypes` and `Schemas` answer with the declared capabilities and the RFC's shapes — iam-svc `ScimProtocolTest` (stub client, RFC 7643 §8 examples verbatim)
- [ ] A user is created, read, filtered by `userName eq`, replaced, patched and disabled with the RFC 7644 shapes and status codes; a duplicate is `409` with `scimType: uniqueness` — `ScimUsersIT`, `ScimProtocolTest`
- [ ] A user matching a hand-made login by a verified email is adopted, not duplicated; an unverified one is `409` — `ScimUsersIT.adoptsAVerifiedMatch`
- [ ] `active:false` and `DELETE` disable the login, refuse its next sign-in and refresh, revoke its tokens, end its POS session and put it on the deny list; `active:true` restores it; a login disabled by an admin is not re-enabled by the directory — `ScimDeprovisionIT`, gateway `DenyListFilterTest`
- [ ] A group's membership change makes and removes exactly the mapped DIRECTORY assignments and none of the manual ones; a redelivered event changes nothing — tenant-svc `DirectoryReconcileIT.onlyDirectoryAssignmentsMove`, `.redeliveryIsANoOp`
- [ ] A mapping to OWNER, or to a custom role holding `staff.manage`, is refused when saved; a directory that tries to deprovision an owner gets SCIM `403 mutability` and the owner sees the issue — `DirectoryMappingIT.ownerTierIsNeverMappable`, `ScimDeprovisionIT.ownersAreNotDeprovisionedByAMachine`
- [ ] Over the staff allowance the login is made, the assignment is not, an issue is recorded and the owner is told once a day — `DirectoryReconcileIT.overTheStaffLimit`, notification-svc `DirectoryIssueHandlerTest`
- [ ] Yesterday's directory token is refused after revoke; a directory token on any route but `/scim/v2/**` is `401`; a tenant's staff token on SCIM is `401` — gateway `ScimRouteTest`, iam-svc `ScimTokenIT`
- [ ] Another business cannot read, patch or delete our users or groups with its own directory token, even naming our user ids (SCIM `404`); nor read our mappings, issues or managed list as owner or manager — `ScimIsolationIT`, `DirectoryMappingIT.otherBusinessSeesNothing` (tenant-isolation line)
- [ ] Turning directory sync on without single sign-on is refused `409 DIRECTORY_SSO_REQUIRED`; with it off every SCIM call is refused — `DirectorySettingsIT`
- [ ] Errors on `/scim/v2/**` are SCIM's own body and `application/scim+json`, everywhere else stay problem details — `ProblemResponseFilterTest.scimIsLeftAlone`
- [ ] Entra ID and Okta quirks (string `"False"`, capitalised ops, `members[value eq …]` paths, PUT updates) are all accepted — `ScimProviderQuirksTest`
- [ ] Flow guard stays green; k6 `directory-sync-flow` drives a provider stub through provision, group change, deprovision

## Screens

- **Back-office → Settings → Directory sync** (owner): an on/off switch (disabled with a plain reason when single sign-on is off), the base URL to copy, **Make a token** (shown once with a copy button, then the prefix and last used; **Revoke**), a table of group mappings (provider group, role, stores or "whole business", **Add**, **Remove**; the role picker has no owner and no `staff.manage` role), **People managed by the directory** (name, roles, last change), and **Needs attention** (the issues, each in words: "Not made a cashier at Store 12: your plan's staff allowance is full").
- **Back-office → Staff:** a small "From directory" badge on a directory-made assignment, with the manual **Remove** replaced by "managed by your directory".
- Words not codes, dates through `AppFormat`, adaptive per UI-GUIDE §7.2; the Flutter widget tests cover the owner-only visibility and the no-owner role picker.

## Decisions

Filled while building.
