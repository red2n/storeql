# Forgot password — reset by email

| | |
|---|---|
| **Status** | BUILT |
| **Author** | the design system's Login card (the one issue it still listed) and the user · 2026-09-26 |
| **Roadmap** | new: the design system's *Screen_SignIn_Login* card, "no way back from a forgotten password" |
| **Services** | iam-svc owns logins and reset tokens · notification-svc sends the email (from an event) · the gateway opens the two routes to the public |
| **Builds on** | `users` (one email may hold a shopper login and staff logins in several businesses), `refresh_tokens.revokeAllForUser`, `PasswordPolicy`, the login card and the storefront's sign-in dialog, `Catalogue` message types, the notification log |
| **Built in** | 577f5106 (branch `test/k6-gross-margin`, 26 Sep 2026) |

## Problem

A person who forgets their password has no way back. The sign-in card offers only *Create an account*; a shopper makes a second account with another address and loses their orders, loyalty and saved addresses; a member of staff asks the owner to take their login away and give it back, which the owner cannot do without removing them. Every forgotten password is a lost customer or a support call.

## Outcome

Every sign-in card has *Forgot password?*. The person types their email and always reads the same answer — *If an account uses that address, we have sent a link* — whether or not one does. The email, in the language they were using, lists each sign-in that uses the address: *your shopper account*, *staff at Corner Stores Ltd*; each has its own link, good for 30 minutes and once. A staff login whose business signs its staff in through its own identity provider is named with *sign in through your business's sign-in* instead of a link. The link opens a page that asks for a new password against the published policy; when it is saved, every other session of that login ends, and the person signs in as usual — the second factor, if they have one, is still asked.

## Who and where

- **Personas** ([PRD §2](../PRD.md)): shoppers; staff of every role (owner, manager, storekeeper, cashier). Not the platform administrator.
- **Channels:** the web app's sign-in card (back office and POS), the storefront's sign-in dialog, email.
- **Scope:** per login. One address may hold a shopper login (no business) and staff logins in several businesses; each is reset on its own.
- **Roles that can write:** anyone, without signing in — the link is the proof.
- **Sandbox tenant:** behaves the same (a sandbox's staff are its business's staff).

## Scope

- **In:**
  - iam-svc: *forgot* (always the same answer, the same work whether or not the address is known, at most a few requests an address an hour, a newer request replacing the older links) and *reset* (one token, one use, 30 minutes; the policy checked; every session of the login ended; audited).
  - The email (notification-svc): the platform's own words, not a business's (a business cannot reword a message that carries a key to its staff's logins), in the language the person used where the platform has it, English otherwise; sent by email only; the copy kept in the notification log never holds the link and belongs to no business.
  - The app: *Forgot password?* on the sign-in card and the storefront's sign-in dialog, the request page, the reset page (`/reset-password/<token>`, no sign-in) showing the password rules before anything is typed.
  - The dev stack's email: a mail catcher (Mailpit) so the stack sends real email and a k6 suite follows the link as a person would.
- **Out, on purpose:**
  - Reset by text message: a login's phone number is never verified, texts are metered to a business (a shopper's login belongs to none), and a text is the weaker channel for a key to a login (NIST SP 800-63B restricts it).
  - The platform administrator: its password and second factor are set by the operator's bootstrap; an email link must not be a way into the platform console.
  - A staff login whose business requires single sign-on: its password is not how it signs in; the business's identity provider owns resets.
  - Someone resetting another person's password (an owner for a member of staff): the link goes only to the address on the login.
  - Changing the email address, security questions, sign-in by link.

## Data and flow

- **Owned by** iam-svc: `password_reset_tokens` (the login, the token's hash, when it expires, when it was used, when it was replaced). The raw token is never stored.
- **Needs from other services:** the business's name for a staff link (`TenantProfiles.businessName`, REST, cached); whether the business requires single sign-on (iam-svc's own `sso` tables).
- **Events published:** `PasswordResetRequested` (iam-svc → notification-svc): the address, the language, and one entry per login — its kind, the business's name for staff, and either its link and expiry or the single-sign-on note.
- **Retryable writes** (Idempotency-Key): none — *forgot* is safe to repeat (a newer link replaces the older), and a token is spent once.
- **New error codes:** `400 PASSWORD_RESET_TOKEN_INVALID` (unknown, expired, used or replaced — one code, so the page cannot tell which); the policy's own codes for the new password (`PASSWORD_TOO_SHORT`, `PASSWORD_TOO_LONG`, `PASSWORD_BREACHED`, `PASSWORD_IS_IDENTITY`); `400 VALIDATION_FAILED` for a request with no address.

## Money, time and limits

- **Currency:** none.
- **Ledger postings:** none.
- **Dates:** the token's expiry is 30 minutes from the request, in UTC; the email says the time it expires.
- **Plan limits:** none.

## Constraints

No account enumeration: *forgot* answers `202` with the same body whatever the address, and does the same work (a token is minted and hashed either way). Tokens are 256-bit random, stored only as a hash, single-use, and die when a newer one is asked for. Multi-tenant: a token resets exactly one login; a staff login of one business is never touched by a request that finds another business's login with the same address, and the email names each business so the person can tell them apart. The link never enters a notification log a business can read. Golden rules: tenant ids come from the login row, never the request; UUIDv7 everywhere; the event goes through the outbox.

## Open questions

- [x] **How long is a link good for?** Recommended: 30 minutes, once. → **30 minutes, once** (the user, 2026-09-26: "I will go with your recommendation")
- [x] **Does a reset sign the person in?** Recommended: no — they sign in with the new password, and the second factor is still asked. → **no** (the user, 2026-09-26)
- [x] **Who can reset?** Recommended: shoppers and staff alike; not the platform administrator; not a business's single-sign-on staff. → **shoppers and staff** (the user, 2026-09-26)
- [x] **What does the email say when one address holds several logins?** Recommended: one email listing each login with its own link, staff logins named by their business. → **one email, one link per login** (the user, 2026-09-26: accepted with the recommendations)

## Acceptance

- [x] *Forgot* answers the same `202` for a known address, an unknown one and a suspended login, and sends a link only for the known active login — `PasswordResetIT.forgotAnswersTheSameWhetherOrNotTheAddressIsKnown`, `aSuspendedBusinessGetsNoEntryAndEarlierLinksAreRefused`
- [x] One address holding a shopper login and staff logins at two businesses gets one email with a link for each, staff named by their business; resetting one leaves the others' passwords unchanged — `PasswordResetIT.oneAddressAcrossTwoBusinessesIsResetIndependently`, `PasswordResetRequestedHandlerTest.twoBusinessesNamesEachAppearOnlyOnTheirOwnEntry`, k6 `password-reset-flow`
- [x] A link resets once: the new password signs in, the old does not, every earlier session is ended, and the second factor is still asked — `PasswordResetIT.aLinkResetsOnceAndEndsEverySession`, `aSecondFactorIsStillAskedAfterReset`, k6 `password-reset-flow`
- [x] A used, expired or replaced link, and a made-up one, are refused alike `400 PASSWORD_RESET_TOKEN_INVALID`; a new password the policy refuses is refused by its code — `PasswordResetIT.aMadeUpTokenIsRefused`, `anExpiredTokenIsRefused`, `aReplacedTokenIsRefusedTheNewerWorks`, `aPolicyRefusalLeavesTheTokenUsable`
- [x] The platform administrator and a single-sign-on staff login get no link; the email says to sign in through the business for the latter — `PasswordResetIT.thePlatformAdministratorGetsNoEntry`, `ssoRequiredStaffGetNoLink`, `PasswordResetRequestedHandlerTest.everyEntryKindIsWordedAndTheSsoEntryCarriesNoLink`
- [x] Another business's owner, manager, cashier and storekeeper cannot read the link: the notification log holds no link and the reset's record belongs to no business — `PasswordResetRequestedHandlerTest.theStoredRowHoldsNoTokenAndBelongsToNoBusiness`, `aLinkWithNoSchemeIsStillNeverStored`, `NotificationIT.thePasswordResetRowIsInvisibleToEveryRoleOfEveryBusiness`, `deletingAnAccountErasesItsPasswordResetRowToo`, `RetentionPurgeIT.passwordResetRowsOlderThanTheConfiguredPeriodAreDeletedWhateverTheLogin`, `PasswordResetIT.theRecordBelongsToNoBusiness`
- [x] More than the allowed requests an hour for one address send nothing more and still answer `202` — `PasswordResetIT.moreThanAllowedRequestsSendNothingMore`, k6 `password-reset-flow`
- [x] The email is in the person's language where the platform has it — `PasswordResetRequestedHandlerTest.aPolishRequestGetsPolishWords`, `anUnknownOrMissingLanguageReadsEnglish`
- [x] The sign-in card and the storefront dialog offer *Forgot password?* (never the platform console); the reset page shows the rules and refuses a short password before sending — widget tests `test/features/auth/forgot_password_screen_test.dart`, `reset_password_screen_test.dart`, `sign_in_forms_test.dart`, `test/features/storefront/storefront_sign_in_test.dart`, `test/core/password_policy_test.dart`, `test/core/router_password_reset_test.dart`
- [x] Through the stack: request, the email arrives, the link resets, sign-in works with the new password only — k6 `password-reset-flow` 62 checks (Mailpit)

## Decisions

- **The link travels in the event, as the dunning pay link does.** iam-svc writes `PasswordResetRequested` through its outbox with each link in it, because notification-svc writes the email; a link lives 30 minutes and once, and nothing in notification-svc keeps it (below). The alternative — notification-svc calling iam-svc for the link — would put a route that hands out keys to logins on the network.
- **One event per request, one email per address, one link per login.** `entries[]` carry `kind` (`SHOPPER`, `STAFF`, `STAFF_SSO`), `userId` and, for staff, `businessName` (an explicit null when tenant-svc cannot be read, so the words say *a business*). A shopper entry never has a business name.
- **Eligibility is the sign-in's own test, asked twice.** At *forgot*: ACTIVE, not the platform administrator, the business active, and not a tier the business signs in through its provider (that entry is named, with no link). At *reset* the same again before the token is spent: a login suspended, its business suspended, or single sign-on required since the link went out gets `PASSWORD_RESET_TOKEN_INVALID`, the one code.
- **The policy is checked before the token is spent**, so a refused password leaves the link usable; the spend is one atomic `UPDATE … WHERE used_at IS NULL AND replaced_at IS NULL AND expires_at > now() RETURNING`, on the same transaction as the new hash, the replacement of the login's other links, the revocation of its refresh tokens and the audit row. Access tokens already issued live out their fifteen minutes.
- **The throttle is per address and can delay that person's reset by at most an hour.** Three accepted requests an hour per SHA-256 of the address (the address itself is never stored); over it still answers `202`. Someone who knows an address can use up its hour — accepted: a limit per client cannot stop a flood aimed at one person from many clients, and the gateway's per-IP limit already bounds any one client. Requests over the limit are not recorded, so they cannot extend it.
- **Email only, through its own sender.** notification-svc's `SmtpAccountEmailSender` is built from the same `storeql.notification.smtp.*` keys and is live only when `storeql.notification.channel` is `email`/`smtp`; never in-app, MQTT or text (the link must never reach a feed). With no transport, or an SMTP failure, the row says `NOT_SENT` and nothing retries — a person asks again. The dev stack now emails through Mailpit; k8s documents the SMTP settings, since a deployment without email cannot send a reset.
- **The kept copy holds no link, belongs to no business, and does not outlive its use.** Each entry's link is replaced as the literal text it is, then anything still shaped like a web address — a base address configured without `https://` would slip past a pattern alone (the review's one blocker). `tenant_id` is null, so no business's log read finds it; it is recorded against the shopper login, so `AccountDeleted` erases it; every reset row is deleted by the platform after 30 days (`storeql.notification.password-reset.retention-days`), which covers staff addresses too.
- **The platform's words, in the app's eight languages.** Not a catalogue message (a business cannot reword a message that carries a key to its staff's logins); en, ar, bn, gu, pa, pl, ro, ur, English for anything else. The Bengali, Gujarati, Punjabi and Urdu wording has not been read by a native speaker yet.
- **Email on in the dev stack showed the channel's weak spot, now closed.** With `storeql.notification.channel=email`, a store alert addressed to a store's id went to the mail server too, was refused (*Invalid Addresses*), and took the in-app copy down with it — retried five times, dead-lettered, the business never told (k6 `chargeback-flow`). The composite channel now emails only a recipient that is an email address (`SmtpChannel.reaches`), the rest stay in-app, and the log records the channel that carried each message (`nameFor`); a send naming `channel: APP` is the in-app feed alone (`Channels`), which the sandbox relies on (k6 `sandbox-flow`).
- **Nothing assumed about where the person is:** the expiry is written in UTC with the offset stated (the reader's zone is unknown); the app sends the language it is running in.
