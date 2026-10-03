# Investigation cases: when alerts and audit entries point at a person, a till or a product, a manager opens a case

| | |
|---|---|
| **Status** | CONFIRMED |
| **Author** | the user, standing instruction "make the whole platform production-ready and industry-standard" · 2026-09-30 |
| **Roadmap** | Readiness Review row 16.7 "Investigation case management" (Loss prevention and shrink, missing); the Oracle audit's loss-prevention gap |
| **Services** | notification-svc owns cases (it already owns the alert inbox this extends) · order-svc, inventory-svc, payment-svc, iam-svc and tenant-svc answer one shared "evidence lookup" for the records their own logs hold · tenant-svc answers who is a manager and at which stores · common-web holds the lookup contract · the app gets a Cases screen and an "Open a case" action on the alert |
| **Builds on** | [exception-alerts](exception-alerts.md) (`exception_alerts`, `evidence` as `{kind, id}`, `ALERT_SUBJECT_IS_CALLER`, the Alerts screen, `Notifier.notifyOnce`, the `EXCEPTION_ALERT` catalogue message), order-svc `AuditTrailResource` and its append-only logs, [report-integrity](report-integrity.md)'s `@SensitiveRead` and access log, [platform-administration](platform-administration.md)'s `AuditFeed` shape and safe-words rule, `RetentionSweeper` and the business's retention setting (holds included), `TenantContext.reportStores`, `BusinessWide.require`, tenant-svc `GET /admin/staff` |
| **Built in** | not yet built |

## Problem

A manager sees an alert ("returns by one cashier over the line"), an exceptions report, an audit-trail entry and a stock count that will not balance. Each points at the same person or till, but the platform keeps them apart. The manager keeps the working in a notebook or a spreadsheet: which records mattered, who else looked, what was said to the person, what was decided. Nothing says who else may see it. Worse, the person under review may be a manager or the owner, and every place the working could be written (an alert acknowledgement note, a chat, a shared sheet) is readable by them. When the outcome is a dismissal or a police referral, there is no record of how the conclusion was reached.

## Outcome

- **A manager opens a case** from an alert, an audit entry, or from nothing (a hunch): it says what it is about (a person, a till, a product, a customer, a store), and starts with a number the business reads out (`CASE-000012`).
- **Evidence is attached by reference**: alerts, audit entries, sales, returns, stock adjustments, counts, till sessions. Nothing is copied. Opening the case shows each piece as the owning service holds it now, and a piece the reader may not see says so.
- **Notes, an owner of the work, an outcome.** Notes are added and never edited. One person is working on it. It closes with one outcome: no issue, training, recovered (an amount, recorded only), or referred (to whom, a free reference).
- **The subject never sees it, and nobody who was not let in does.** Owners see every case except one about themselves; a manager sees the cases they opened or that an owner named them on. To everyone else a case does not exist (`404`).
- **It is the alerts inbox, not a second one.** An alert has "Open a case"; a case lists its alerts; acknowledging an alert works exactly as before.
- **The record is complete and append-only**: every open, attach, note, hand-over, grant and close is on the case's own log with who and when.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): the **owner** (sees all cases but their own; names who else may see one), the **store manager** and **area manager** (open, work and close cases). The **subject** (a cashier, storekeeper, manager or owner) is the person the platform hides the case from. Cashier, storekeeper and shopper never reach it.
- **Channels:** back-office (Cases screen, the "Open a case" action on the Alerts screen and on an audit-trail row). Email only tells a person they were given access, never what it is about.
- **Scope:** a case is **store-level** (it names a store) or **business-wide** (no store). Who may open one at all follows the store as everything else store-held does; who may *see* one is an explicit list (below).
- **Roles that can write:**
  - **Open, add evidence, add a note, hand over, close:** OWNER; a business-wide MANAGER; a MANAGER held to stores, for a case at one of their stores whose evidence they may see (`requireStoreAccess`; a store outside theirs is `403 STORE_ACCESS_DENIED`). Everyone else `403` by the shared `/admin` gate.
  - **Grant or revoke who else may see it:** OWNER only. A control that a manager could widen is not a control; the opener is always on the list.
  - **Reopen a closed case:** OWNER only, with a reason.
  - **A subject cannot act on their own case, or on any case naming them** (`404`, since they must not learn it exists; `403 INVESTIGATION_SUBJECT_IS_CALLER` only on open, where they name themselves).
- **Sandbox tenant:** behaves the same. Email suppressed as everywhere in a sandbox (`TenantProfiles.Profile.sandbox()`).

## Scope

- **In**, in build order; each slice is built and tested alone:
  1. **The case (notification-svc).** Tables, numbering per business, open, get, list, notes, assignee, close with outcome, reopen, the visibility list, and the append-only case log. No evidence yet beyond free text. In-app and email notice to someone given access. Testable with no other service.
  2. **Evidence by reference (common-web, order-svc, inventory-svc, payment-svc, iam-svc, tenant-svc).** The shared `EvidenceLookup` contract: `GET /admin/<service>/evidence/{kind}/{id}` answers `{kind, id, storeId, actorId, occurredAt, summary}` with `summary` only from the safe-words list (the audit feed's rule: never an address typed at a sign-in, a secret, a hash, a token, a link), or `404`. Owners by kind: `AUDIT_ENTRY` (the trail's own service in `source`: order-svc, iam-svc or tenant-svc), `ORDER` and `RETURN` (order-svc), `ADJUSTMENT` and `COUNT` (inventory-svc), `TILL_SESSION` (payment-svc), `ALERT` (notification-svc itself). Attaching verifies through the lookup (short timeout, breaker) and the caller's store access at attach; the app resolves the same lookup at read. A kind another service adds later joins by adding its lookup and one row in the catalogue.
  3. **From an alert (notification-svc, app).** "Open a case" on an alert: subject and store come from the alert, the alert and its own evidence list are attached, and the alert row shows the case number to those who may see the case. The alert's acknowledgement is untouched.
  4. **Controls and hygiene (notification-svc, common-web).** The `investigation-case` sensitive-read key (a read of a case file is logged in the report access log), retention, the two alert metrics below, the per-subject history ("earlier cases about this person", shown only to those who may see each), and the erasure declaration.
  5. **Screens (app).** Cases list, case file, the alert and audit-row entry points.
- **Out, on purpose:**
  - **A server-built case pack (PDF or export) for an employer, insurer or authority.** Building it server-side would mean notification-svc reading every owning service's records with the caller's authority. The case file is printed from the app, which has already resolved each piece under the reader's own rights. A referral is a free reference the business enters (a court, an authority, an insurer), so no country's process is assumed.
  - **CCTV or EAS links (Review row 16.6).** A separate integration. A piece of evidence of kind `NOTE` can carry a free reference to a clip; nothing is stored or fetched.
  - **Known-loss and unknown-loss separation (row 16.8).** Stock accounting, not case work; belongs to the shrinkage posting rule of [stock-counts](stock-counts.md).
  - **Collecting a recovered amount.** The case records that an amount was recovered (from whom is free text in the note); it posts nothing to the ledger and moves no money. Deducting from pay or invoicing someone are the business's own acts, outside the platform.
  - **Cross-business case sharing, or the platform reading cases.** The platform administrator sees no tenant alert or case content (isolation), as on the alerts page.
  - **Automatic case opening from an alert.** A rule that opens cases would make accusation a machine's act. A person decides that an alert is worth a case.
  - **Editing or deleting a note, evidence link or case.** The case is a record; a mistaken note is answered by a later note and a withdrawn evidence link (a log entry, the link stays visible as withdrawn).

## Data and flow

- **Owned by notification-svc** (every table `tenant_id UUID NOT NULL`, index starting `(tenant_id, …)`, ids v7 with the common CHECKs):
  - `investigation_cases`: `id`, `tenant_id`, `case_no` (from `case_series`, per business, gap-free is not promised), `title` (short free text), `store_id` (null = business-wide), `status` (OPEN, CLOSED), `assignee_id` (a staff login, null until set), `opened_by`, `opened_at`, `closed_by`, `closed_at`, `outcome` (NO_ISSUE, TRAINING, RECOVERED, REFERRED; null while open), `recovered_amount` NUMERIC(18,4) and `recovered_currency` (only with RECOVERED; the business's home currency, or the currency the loss was in, stored, never translated), `referred_to` (free text, only with REFERRED), `outcome_note`, `version`. Mutable summary; the truth of what happened is the log.
  - `case_subjects`: `case_id`, `subject_kind` (STAFF, TILL, VARIANT, CUSTOMER, STORE), `subject_key` (an id, never a name). One or more per case. **Every STAFF subject is excluded from seeing the case, whatever their role.**
  - `case_evidence`: `case_id`, `kind`, `ref_id`, `source` (the owning service), `store_id` (as the lookup answered at attach, for filtering), `note`, `added_by`, `added_at`, `withdrawn_at`, `withdrawn_by`. Unique `(case_id, kind, ref_id)`. No copy of the record, only its identity and where it lives.
  - `case_access`: `case_id`, `user_id`, `granted_by`, `granted_at`, `revoked_at`. The opener and the current OWNERs (other than subjects) are implied and not listed; this table holds the managers an owner names.
  - `case_events` (append-only): `id`, `case_id`, `type` (OPENED, EVIDENCE_ADDED, EVIDENCE_WITHDRAWN, NOTE, ASSIGNED, ACCESS_GRANTED, ACCESS_REVOKED, CLOSED, REOPENED), `actor_id`, `at`, `body` (a note's text, the outcome, a reason), `ref` (an evidence key). Every change above writes exactly one, on the same transaction.
  - `case_series` (per business next number).
- **Needs from other services:** evidence lookups (REST, above); `GET /admin/staff` from tenant-svc (who is a manager or owner, at which stores) to validate an assignee or grant and to find the owners to notify; iam-svc for a person's email when they are given access. Never a join.
- **Events published:** none of its own. Two alert metrics raise `ExceptionAlertRaised` (below). The access-log entry rides `SensitiveReportRead` (`storeql.notification.report-read`) through notification-svc's own outbox, which becomes the fifth serving service on [report-integrity](report-integrity.md) slice 6.
- **Visibility rule, in one place (`CaseAccess`, pure):** a caller sees a case only if they are not one of its subjects **and** (they are an OWNER, or the opener, or on `case_access` unrevoked) **and**, if they are a store-held MANAGER, the case is at one of their stores or is business-wide and they were named. Every read of a case, a list, a count and a search applies it in the SQL, not after. A case a caller may not see is `404 INVESTIGATION_NOT_FOUND`, the same as another business's.
- **Retryable writes (Idempotency-Key):** open, attach evidence, add a note, hand over, close, reopen, grant. A retry answers with the first.
- **Endpoints (notification-svc, `/admin/investigations`, management by the path gate):**

| Method and path | Roles | Notes |
|---|---|---|
| `POST /admin/investigations` `{title, storeId?, subjects[], fromAlertId?}` | OWNER, MANAGER | `Idempotency-Key`; `400 INVESTIGATION_SUBJECT_REQUIRED`; `400 INVESTIGATION_SUBJECT_INVALID`; `403 INVESTIGATION_SUBJECT_IS_CALLER` |
| `GET /admin/investigations?status=&storeId=&subjectKey=&assignee=&after=&limit=` | same | cursor `(opened_at, id)`, default 20 / max 100; visibility applied in SQL |
| `GET /admin/investigations/{id}` | same | case, subjects, evidence refs, access list (OWNER only sees the access list), log |
| `POST /admin/investigations/{id}/evidence` `{kind, refId, note?}` | same | `404 INVESTIGATION_EVIDENCE_NOT_FOUND`, `409 INVESTIGATION_EVIDENCE_DUPLICATE`, `503 INVESTIGATION_EVIDENCE_UNVERIFIED`, `403 STORE_ACCESS_DENIED` |
| `POST /admin/investigations/{id}/evidence/{kind}/{refId}/withdraw` `{reason}` | same | a log entry; the link is shown as withdrawn |
| `POST /admin/investigations/{id}/notes` `{body}` | same | `400 INVESTIGATION_NOTE_REQUIRED` |
| `PUT /admin/investigations/{id}/assignee` `{userId}` | same | `422 INVESTIGATION_ASSIGNEE_INVALID` (not an owner or manager, is a subject, or has no access) |
| `PUT /admin/investigations/{id}/access` `{userIds[]}` | OWNER | replaces the named list; `422 INVESTIGATION_ACCESS_INVALID` (a subject, or not a manager) |
| `POST /admin/investigations/{id}/close` `{outcome, note, recoveredAmount?, currency?, referredTo?}` | same | `400 INVESTIGATION_OUTCOME_REQUIRED`, `400 INVESTIGATION_OUTCOME_INVALID`, `409 INVESTIGATION_CLOSED` |
| `POST /admin/investigations/{id}/reopen` `{reason}` | OWNER | `409 INVESTIGATION_NOT_CLOSED` |
| `GET /admin/investigations/summary?storeId=` | same | `{open, byAssignee, oldestOpenDays}` for the badge |

Resources stay thin; logic in `service/`; DTOs both ways. Every case, evidence, access and outcome answer names ids; the app resolves names through `staffLoginsProvider` and `variantLabelsProvider`.

- **Notifying.** Being named on a case, or handed one, sends one in-app notice and one email in the platform's own words, **never containing the title, subject or evidence** ("You were given access to a case. Open it in the app."), so an email in a shared inbox reveals nothing. Once per `(user, case, grant)` through `Notifier.notifyOnce`.
- **New alert metrics** (rows for [exception-alerts](exception-alerts.md)'s catalogue, owner notification-svc): `investigations.opened` (COUNT by the opening manager or owner in a window; catches the tool used to harass someone; subject STAFF) and `investigations.open_age` (MAX, days since the oldest still-OPEN case was opened, sweep-only; subject BUSINESS). Both are business-wide alerts, so only owners and business-wide managers receive them, and the recipient list still leaves out any subject of the case concerned.
- **New error codes:** those in the table, plus `404 INVESTIGATION_NOT_FOUND`.

## Money, time and limits

- **Currency:** the recovered amount is in the currency the loss was in and stored beside it; the platform translates nothing and applies no rate.
- **Ledger postings:** none.
- **Dates:** UTC instants; the app shows the store's zone (`TenantProfiles.Stores.zoneOf`) and the age of a case in whole days computed in that zone.
- **Plan limits:** none. No finding asked, and a business's own investigations are not a metered thing.

## Constraints

- **Golden rules:** database-per-service (evidence is identity plus a lookup, never a copy or a join); tenant from the JWT and first in every query; append-only (`case_events`, and `case_evidence` and `case_access` change only by adding `withdrawn_at`/`revoked_at`, each with a log row); thin resources; UUIDv7 with `Ids.parse` on every id read; idempotent writes.
- **Existing tenants:** no case exists until a manager opens one; alerts, the audit trail and the exceptions report behave exactly as before.
- **Personal data:** the case stores ids and the business's own free text. Erasing a customer leaves a subject key that reads "erased" (the alerts rule). The free-text columns (`title`, note bodies, `outcome_note`, `referred_to`) are declared in notification-svc's `SubjectDataSpec` as RETAIN with the reason "the business's record of an inquiry may be needed to establish or defend a legal claim", reviewed once by the privacy page's schema test. Where a jurisdiction's data (`Jurisdictions`) forbids that retention for a business, its `SubjectDataSpec` override erases them; none is assumed.
- **Retention:** a closed case follows the business's retention setting (the same class the alert inbox uses; the platform picks no number); an open case is never purged; a retention hold ([retention holds](platform-administration.md)) blocks purging a case it covers.
- **Location-neutral:** no country's disciplinary or referral procedure assumed; outcomes are four neutral words the business renders in its own, and `referred_to` is free text.
- **Backups:** plain tables, no function in a CHECK; `scripts/backup-drill.sh` needs no special step, run once at slice 1.

## Open questions

- [x] Is a case a second inbox? Recommended: no; it extends the alert inbox and lives beside it. → **A case extends the alerts inbox in notification-svc; an alert links to its cases; acknowledgement unchanged** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Evidence: copy or reference? Recommended: reference, verified when attached and resolved at read under the reader's rights. → **By reference through one shared lookup; nothing copied** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Who may see a case? Recommended: never the subject; owners; the opener; managers an owner names. → **As stated; a non-viewer gets `404`** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Should closing with "recovered" or "referred" need a second person? Recommended: no; both only record a fact, no money moves, nothing is sent to anyone. If the business wants a second look it reads the log. → **No approval key** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Can an owner be a subject? Recommended: yes; other owners see it; the subject owner does not. If they are the only owner, the opener and the managers they name are the audience. → **Yes** (industry standard, under the user's standing instruction of 2026-09-30)
- [x] Is reading a case a sensitive read? Recommended: yes, it holds allegations and links to the audit trail and exceptions. → **`investigation-case` is added to the fixed sensitive-read catalogue on the report-integrity page** (industry standard, under the user's standing instruction of 2026-09-30)

## Acceptance

Tests are named before building; tick with the real test and count when BUILT.

**Slice 1: the case**
- [ ] A manager opens a case with a subject and store; it gets the next number for that business and one `OPENED` log entry — `InvestigationCaseIT.openAssignsNumberAndLogs`
- [ ] Notes are added and never edited; each writes a log entry with author and time; there is no update or delete route — `InvestigationCaseIT.notesAppendOnly`
- [ ] Closing needs an outcome and a note; `RECOVERED` needs an amount and currency, `REFERRED` needs `referredTo`; closing a closed case is `409 INVESTIGATION_CLOSED`; only an owner reopens, with a reason — `InvestigationCaseIT.closeAndReopen`
- [ ] A retry of open, note, close and grant with the same `Idempotency-Key` answers with the first and adds nothing — `InvestigationCaseIT.retriesAreOnce`
- [ ] The subject, whatever their role, gets `404` on list, get and every write, and is absent from every count — `InvestigationCaseIsolationIT.subjectSeesNothing`
- [ ] A manager not named and not the opener gets `404`; an owner sees all but their own; naming a manager is owner-only (`403` for a manager) — `InvestigationCaseIsolationIT.visibilityList`
- [ ] A store-held manager cannot open a case at another store (`403 STORE_ACCESS_DENIED`) nor see a business-wide case unless named — `InvestigationCaseIsolationIT.storeHeld`
- [ ] Another business's owner and manager, and this business's cashier, storekeeper and a shopper, naming our case id, get `404`/`403` and nothing changes — `InvestigationCaseIsolationIT.otherBusinessAndOtherRoles`
- [ ] A person opening a case naming themselves is `403 INVESTIGATION_SUBJECT_IS_CALLER`; an assignee or grantee who is a subject is `422` — `InvestigationCaseIT.subjectCannotBeInvolved`
- [ ] The access notice is sent once, in-app and by email, and contains no title, subject or evidence; a sandbox sends nothing out — `CaseNoticeIT.noticeRevealsNothing`
- [ ] The visibility rule is one pure class with the truth table proved — `CaseAccessTest`

**Slice 2: evidence**
- [ ] Attaching an order, a return, an adjustment, a count, a till session, an audit entry and an alert each verifies through its owner and stores no copy — `CaseEvidenceIT.attachByReference` (with each owner's `EvidenceLookupIT`)
- [ ] A record that does not exist, or belongs to another business, is `404 INVESTIGATION_EVIDENCE_NOT_FOUND`; an unreadable owner is `503 INVESTIGATION_EVIDENCE_UNVERIFIED` and nothing is attached; a duplicate is `409` — `CaseEvidenceIT.refusals`
- [ ] A store-held manager cannot attach evidence from another store — `CaseEvidenceIT.storeHeldEvidence`
- [ ] A lookup summary contains only allow-listed words — `EvidenceLookupSafetyTest` (common-web)
- [ ] Withdrawing keeps the link, marked withdrawn, with who and why — `CaseEvidenceIT.withdrawIsALogEntry`
- [ ] Widget: the case file resolves each piece and says "not available to you" for one the reader may not see — `case_file_test.dart`

**Slice 3: from an alert**
- [ ] Opening a case from an alert attaches it and its evidence, and the alert row shows the case number only to those who may see the case — `CaseFromAlertIT.linkAndVisibility`
- [ ] Acknowledging the alert (with its note) is unchanged and independent of the case — `CaseFromAlertIT.acknowledgementUnchanged`

**Slice 4: controls**
- [ ] Opening a case file records one access-log entry with `investigation-case`; the subject cannot read the log entry naming them (they cannot read the log at all) — `CaseAccessLogIT.aReadIsLogged`
- [ ] `investigations.opened` and `investigations.open_age` raise business-wide alerts that omit a case's subject from recipients — `CaseAlertIT.metricsAndRecipients`
- [ ] A closed case is purged only after the business's retention and never under a hold; an open case never — `RetentionSweeperTest.investigationCases`
- [ ] Notification-svc's free-text columns are declared in its `SubjectDataSpec` — `SubjectDataSpecTest`
- [ ] Prior cases about a subject show only those the caller may see — `InvestigationCaseIT.priorCasesRespectVisibility`

**Slice 5: screens**
- [ ] Widgets: Cases list (filters, badge), case file (evidence, notes, hand over, close with outcome), "Open a case" on the alert and on an audit row, owner's visibility dialog — `cases_screen_test.dart`, `alert_open_case_test.dart`; k6 `investigation-case-flow` (alert, case, evidence, close); flow guards stay green

## Screens

- **Admin shell, Alerts → Cases:** a list (number, title, store by name, assignee by name, status, age in days) with filters and an open-count badge. **Case file:** header (number, status, store, assignee), evidence as cards resolved at read (an order card, a return card, an audit row), each with who attached it; notes newest first with an add box; a log tab; **Hand over**, **Close** (outcome picker, amount in the case's currency through `AppFormat.money`, a referral box) and, for an owner, **Who can see this** and **Reopen**. Empty and error states from `EmptyState`/`ErrorView`; dates through `AppFormat`; words not codes (`status_labels.dart`).
- **Alerts screen and Audit-trail rows:** an **Open a case** action (management only).

## Flow Tests entry

Area `customer`, flow file `target/flow-catalogue/customer/rpt-investigation-cases.json` (capability row 16.7). Cases, each with the test that covers it:
- **Happy:** open from an alert with evidence, work, close as TRAINING — `InvestigationCaseIT`, `CaseFromAlertIT`, k6 `investigation-case-flow`; recovered and referred outcomes carry their fields — `closeAndReopen`.
- **Negative:** no subject, note missing, outcome missing or inconsistent, closed twice, evidence not found or unverifiable, duplicate evidence — `InvestigationCaseIT`, `CaseEvidenceIT.refusals`.
- **Override:** an owner grants a manager access and reopens a closed case with a reason — `InvestigationCaseIsolationIT.visibilityList`, `InvestigationCaseIT.closeAndReopen`.
- **Isolation:** the subject (manager and owner alike), an unnamed manager, a store-held manager at another store, another business's staff of every role, this business's cashier, storekeeper and a shopper — `InvestigationCaseIsolationIT` (all five), `CaseEvidenceIT.storeHeldEvidence`.
- **Edge:** a retry with the same key; the only owner as subject; erased customer subject; a case with two staff subjects; a retention hold — `retriesAreOnce`, `subjectCannotBeInvolved`, `RetentionSweeperTest`.
- **Audit:** the log has one entry per change with actor and time; the file read is in the access log; notes cannot be edited — `notesAppendOnly`, `CaseAccessLogIT`.

## Decisions

- (2026-09-30 evening, reconciliation of the second set of pages) **The word "case" means two unrelated things.** This page's *investigation cases* (notification-svc, routes `/admin/investigations`, codes `INVESTIGATION_*`, alert metrics `investigations.opened` and `.open_age`, sensitive-read key `investigation-case`) and [customer-service-cases](customer-service-cases.md)' *service cases* (customer-svc, routes `/cases`, codes `CASE_*`, metrics `service_cases.*`, permission `cases.handle`). Neither links to the other and a service case is never evidence in an investigation. This page's first draft used `/admin/cases`, `CASE_*` and `cases.*`; all replaced. Evidence lookup (`EvidenceLookup`) is built once in common-web (Stage 0 of the plan).

- **2026-09-30, cases live in notification-svc, beside the alert inbox.** Rejected: a new service (an inbox, staff lookups and email would be built twice) and each owning service (seven case screens). The cost is one shared lookup contract.
- **2026-09-30, the subject's exclusion is in the query, not the screen.** The rule is applied in SQL on every read, count and search, so no future screen can forget it.
- **2026-09-30, no approval and no money.** Outcomes record facts. `RECOVERED` posts nothing; approvals keys are for actions that move money, stock or access.
