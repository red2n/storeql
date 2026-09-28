# Accounting connectors

How a business's books leave the platform (17.9): every journal the nominal ledger posts is pushed,
once, to the accounting package the business keeps its books in — Xero, QuickBooks Online or Sage
Business Cloud Accounting — as that package's own journal, with what came back kept on a log.

## What is pushed

purchase-svc owns the nominal ledger and the connectors. A **journal** is one balanced posting: the
lines of a goods receipt, a supplier invoice, a credit note, a sale, a tender, a refund, a payment
run, a settlement, a deferred-revenue release, or a manual journal. From the day the owner chooses
(`syncFrom`), every journal is queued the moment it exists and pushed by the clock — every thirty
seconds — or on request (`POST /accounting/connection/sync`). Each push is one journal → one journal
in the package, with the journal's lines mapped onto the package's accounts:

| Ours | Xero | QuickBooks Online | Sage Business Cloud |
|---|---|---|---|
| a journal | `PUT /api.xro/2.0/ManualJournals` (one `ManualJournal`, `POSTED`) | `POST /v3/company/{realmId}/journalentry` (one `JournalEntry`) | `POST /v3.1/journals` (one `journal`) |
| a line's amount | `LineAmount` positive for a debit, negative for a credit | `Amount` unsigned, `PostingType` Debit or Credit | `debit` and `credit` |
| the account | `AccountCode` | `AccountRef.value` (the account's id) | `ledger_account_id` |
| the date, the words | `Date`, `Narration` | `TxnDate`, `PrivateNote` | `date`, `description`, `reference` (our journal id) |
| idempotency | `Idempotency-Key: <journal id>` | `requestid=<journal id>` | none — see *uncertain* below |
| the chart | `GET /api.xro/2.0/Accounts` | `query select * from Account` | `GET /v3.1/ledger_accounts`, paged |
| who | `xero-tenant-id` | the realm in the path; `environment` PRODUCTION or SANDBOX picks the host | `X-Business` |
| tokens | OAuth 2.0 bearer; refresh at `identity.xero.com/connect/token` | OAuth 2.0 bearer; refresh at `oauth.platform.intuit.com/oauth2/v1/tokens/bearer` | OAuth 2.0 bearer; refresh at `oauth.accounting.sage.com/token` |

Amounts are pushed as the ledger holds them: the ledger has already split VAT onto its own codes,
so no tax is computed in the package (Xero lines carry `TaxType: NONE`).

The **`SIMULATED`** package is the platform standing in for one: it delivers in-process, its chart
is the ledger's own codes prefixed `SIM-`, and a `refuse` setting names one account it does not
know, so a refusal and its retry can be rehearsed on a stack with no package connected. k6's
`accounting-connector-flow` drives it; the three real packages are proved against stubs of their
APIs in purchase-svc's tests.

## Connecting

The owner connects one package per business (`PUT /accounting/connection`): the provider, the
settings it needs (`tenantId` for Xero, `realmId` and optionally `environment` for QuickBooks,
`businessId` for Sage), the tokens it issued, and `syncFrom`. Tokens are sealed under
`storeql.accounting.secrets-key` (16, 24 or 32 bytes, base64; `openssl rand -base64 32`) and never
shown again; with no key, connecting a real package is refused (`503 ACCOUNTING_NOT_CONFIGURED`)
and the stand-in still works. Given a refresh token with the client id and secret, the access token
renews itself (RFC 6749 §6) a minute before it runs out; a refresh the package refuses stops pushes
with the package's words on the connection's `lastError`, and needs a person.

Where the tokens come from today: the package's developer console. Xero's *custom connections*
issue a client id and secret that mint a token for one organisation; Intuit's developer portal gives
a token for a sandbox or production company through its OAuth playground; Sage's developer portal
does the same for a business. The browser-based consent flow that would mint these from inside the
app is what a later revision adds; the connector itself — what is pushed, how, and what happens when
the package says no — does not change.

## Mapping

`GET /accounting/connection/accounts` reads the package's chart live (which also proves the
connection). `PUT /accounting/connection/mappings` sets the whole mapping of the business's nominal
codes onto the package's account identifiers — Xero's codes, QuickBooks' ids, Sage's ids. A code
left unmapped is sent as itself, which suits a business whose Xero chart uses the same codes; a
package that does not know the account refuses the journal, and the log says so.

## The log, and what needs a person

Every journal's push is a **sync** (`GET /accounting/syncs`, newest first), with its tries:

- `PENDING` — queued, or refused and waiting: five tries by the clock, a minute, five, thirty, two
  hours and eight apart, the reason on the record each time.
- `DELIVERED` — in the package, under the id the package gave it.
- `FAILED` — five refusals, or a refusal that needs a person (the package no longer accepts the
  tokens). Fix the cause and `POST /accounting/syncs/{id}/retry`.
- `UNCERTAIN` — the push may have reached a package that offers no idempotency key (Sage) and no
  answer came back. A second try could book the journal twice, so the clock leaves it: a person
  checks the package and either retries or skips.
- `SKIPPED` — left out with a reason (`POST /accounting/syncs/{id}/skip`): entered by hand, or not
  wanted there. A delivered journal is never pushed again (`409 ACCOUNTING_SYNC_DELIVERED`).

The connection carries the counts, `lastSyncAt` and the first thing that went wrong on the last
pass; switched off (`POST /accounting/connection/disable`) nothing is queued or pushed, switched on
what the ledger posted meanwhile goes on the next pass. Disconnecting removes the tokens, the
mapping and the log; journals already in the package stay there. Management (OWNER, MANAGER) reads
everything; connecting, disconnecting and switching are the owner's; mapping, pushing, retrying and
skipping need the `finance.journal` permission, like posting a journal does.

## In the export

A business's export (21.14) carries its connection, mapping and log without the tokens, and an
import skips them: the destination connects its own package and pushes from there.
