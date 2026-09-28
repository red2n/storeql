# <Feature name — the roadmap row's words>

| | |
|---|---|
| **Status** | DRAFT <!-- DRAFT → CONFIRMED → BUILT, or DROPPED (say why under Decisions) --> |
| **Author** | <who asked for it> · <YYYY-MM-DD> |
| **Roadmap** | <Readiness Review row and horizon, or "new: <who raised it>"> |
| **Services** | <owner> owns the data · <others> read it through REST or events |
| **Builds on** | <existing tables, events, screens it extends: `inventory_batches`, `GoodsReceived`, Procurement screen> |
| **Built in** | <commit or PR, when BUILT> |

<!-- One page. Plain words a store owner could check. No SQL, no endpoint lists:
     those go to the migration and docs/API-GUIDE.md. Delete these comments when filling.
     Keep every heading; write "none" when a section does not apply, so a reviewer
     can see it was considered. -->

## Problem

<!-- Who has the problem today, what they do instead, and what it costs them. One paragraph. -->

## Outcome

<!-- What is true when this is done, observable by the person above. Not a list of endpoints. -->

## Who and where

- **Personas** ([PRD §2](../PRD.md)):
- **Channels:** <!-- ONLINE · POS · back-office · platform console -->
- **Scope:** <!-- tenant-wide · per store · per zone; which store a request names -->
- **Roles that can write:** <!-- OWNER · MANAGER · STOREKEEPER · CASHIER; any new permission -->
- **Sandbox tenant:** <!-- behaves the same, or how it differs (TenantProfiles.Profile.sandbox()) -->

## Scope

- **In:**
- **Out, on purpose:** <!-- what someone will assume is included and is not, with why -->

## Data and flow

- **Owned by** <service>: <!-- new tables, and which are append-only -->
- **Needs from other services:** <!-- what, from whom, REST or event; never a join -->
- **Events published:** <!-- PascalCase past tense, and who consumes them -->
- **Retryable writes** (Idempotency-Key): <!-- which -->
- **New error codes:** <!-- SERVICE_THING_REASON, and the status each answers with -->

## Money, time and limits

- **Currency:** <!-- home currency only · shown in another (display) · translated through FxRates, failing closed -->
- **Ledger postings:** <!-- Dr/Cr accounts and when, or none -->
- **Dates:** <!-- which instant is recorded; any period default -->
- **Plan limits:** <!-- an entitlement key and the service that refuses it, or none -->

## Constraints

<!-- Golden rules this presses on, regulation, volumes, and anything that must not change for existing tenants. -->

## Open questions

<!-- Only what the repo cannot answer and a person must decide. Give your recommended answer
     so it can be accepted as-is. Keep the question when answered and append the answer.
     No code starts while a box is empty.
- [ ] Question? Recommended: … → **answer** (who, YYYY-MM-DD) -->

## Acceptance

<!-- Each line is a behaviour a test proves. Name the test before building; tick it with the real
     test (and count) when BUILT. Include at least one tenant-isolation line and one refusal line.
- [ ] A buyer can … — `RfqIT.awardRaisesOneDraftPerSupplier`
- [ ] Another tenant cannot see … — `RfqIT.…`
- [ ] Awarding twice is refused `409 PURCHASE_RFQ_NOT_ISSUED` — k6 `rfq-flow` -->

## Decisions

<!-- Filled while building: what was settled that the next person would otherwise re-derive or
     undo, each with its why. A rule every session must obey, even when not working on this
     feature, also gets a single line in CLAUDE.md that links here. -->
