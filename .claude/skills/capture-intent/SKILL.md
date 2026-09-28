---
name: capture-intent
description: Capture a StoreQL feature as intent/<slug>.md before building it — problem, outcome, scope, owning service, open questions, acceptance tests — get the open questions answered, then keep its Decisions current while building. Use at the start of a readiness-review roadmap item or any new feature, and read the matching intent file before changing an area that has one.
---

# Capture a feature's intent (before code, kept after)

A roadmap row is one line. Everything built on it rests on how that line is read: the owning service, what is out of scope, which events are needed, how money behaves. This skill puts that reading on one page ([intent/TEMPLATE.md](../../../intent/TEMPLATE.md)) and has the user confirm it **before** any code exists. The same page then holds the decisions made while building. Folder rules: [intent/README.md](../../../intent/README.md).

## When

- **Use it** when starting a roadmap item (the Readiness Review's "What to do, in order"), or any new feature that adds a table, an endpoint family, an event or a screen.
- **Skip it** for bug fixes, refactors, doc fixes and the small follow-ups. Those start from a failing test.
- **Read side:** before changing an area, check whether a page already covers it (see *Reading an intent* below).

## Steps

1. **Look for an existing page.** Run `ls intent/` and `grep -ril "<noun>" intent/`, using the feature's key nouns (table, event, screen). If a page exists, update it instead of starting a second one.

2. **Gather what the repo already answers.** Never ask the user something the code or docs settle.
   - The roadmap row's exact words and grade, or the user's own description.
   - **Owner:** find the service that already holds the nearest tables, in [docs/API-GUIDE.md](../../../docs/API-GUIDE.md) and [ARCHITECTURE §10](../../../docs/ARCHITECTURE.md#10-the-business-services). Rule #1 decides the rest: other services get the data through REST or events, never a join.
   - **Builds on:** grep for the tables, events and screens it extends, and name them.
   - **Rules it presses on:** the [CLAUDE.md](../../../CLAUDE.md) convention bullets that share its nouns, for example money → *Exchange rates*; stock → *Consignment*, *Bonded*, *Fresh yield*; allowances → *Plan limits*; anything tenant-wide → *API versions and the sandbox*.
   - **Already open elsewhere:** [PRD §11](../../../PRD.md), for questions that were raised before.

3. **Draft `intent/<slug>.md` from the template with status `DRAFT`.** The slug is the roadmap row in kebab case.
   - Fill every section. Write "none" rather than deleting one, and remove the template's comments.
   - Write Problem and Outcome in the business's words, not as endpoints.
   - Always fill **Out, on purpose**. It is where most wrong assumptions get caught.
   - **Open questions:** include only what the repo cannot answer and a person must decide (policy, money, who may do it, scope). Give each a recommended answer, so the user can accept it as-is.
   - **Acceptance:** each line is an observable behaviour plus the test that will prove it (unit, IT, k6 or widget). Include at least one tenant-isolation line and one refusal line with its error code.

4. **Put it to the user before any code.**
   - Give a short summary in chat and link the file.
   - Ask the open questions. Use AskUserQuestion when a question is a choice between 2–4 options, with the recommendation first.
   - **Write no code while an open question is unanswered.**

5. **Record the answers and set `CONFIRMED`.** Keep each question and append the answer: `→ **answer** (who, YYYY-MM-DD)`. If an answer changes the scope, update Scope and Acceptance to match.

6. **Build** with the usual skills (`add-endpoint`, `add-event`, `scaffold-service`, `migration`).
   - Whenever something gets settled that the next person would otherwise re-derive or undo (an ordering, a refusal, why a shortcut was not taken), add it to **Decisions** with its why.
   - If the build shows the confirmed scope was wrong, stop and tell the user. Once they agree, update the page. Never let the page and the code drift apart.

7. **Finish and set `BUILT`.**
   - Tick each Acceptance line with the real test name, and the k6 count where there is one.
   - Fill **Built in** with the commit.
   - For a decision that every future session must obey, even sessions not working on this feature, add **one** line to CLAUDE.md's conventions that links to the page. The page holds the detail, not CLAUDE.md.

## Reading an intent (any change, not only new features)

- Before changing a table, event, endpoint family or screen, run `grep -ril "<its name>" intent/` and read what comes back. **Decisions** and **Out, on purpose** hold the "why" that the code doesn't show.
- If a requested change contradicts a Decision, tell the user before making it. Once they agree, update the page's Decisions (keep the old one and add what replaced it and why).

## Self-check

- [ ] One page, no duplicate for the same feature, and every section filled or "none".
- [ ] Owner service named; everything from other services comes through REST or events.
- [ ] Out, on purpose is not empty.
- [ ] Every open question answered by the user before code, and the status is `CONFIRMED`.
- [ ] Every acceptance line names its test; there is a tenant-isolation line and a refusal line.
- [ ] At the end: `BUILT`, acceptance lines ticked with real tests, Decisions recorded, and any global rule added to CLAUDE.md as a one-line link.
