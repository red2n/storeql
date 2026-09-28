# intent/

One page per feature, written **before** it is built and kept after. Each page says what problem the feature solves, what "done" looks like, what is left out on purpose, the questions a person had to decide, the tests that prove it, and the decisions made while building it.

This folder is Stage 1 of Claude Academy's [AI-native SDLC Playbook](https://academy.claude.com/courses/ai-native-sdlc-playbook/capture-intent). It exists for two reasons:

1. **Settle scope before code.** A roadmap row is one line. Everything built on top of it (migration, events, screens, k6 tests, docs) rests on how that line was read. The page puts that reading in front of a person while it is still cheap to correct.
2. **Keep the "why" findable.** The code shows what a feature does. This page records why it does it that way and what was left out on purpose, so a later change doesn't undo a decision nobody wrote down.

## Lifecycle

| Status | Means | Next step |
|---|---|---|
| `DRAFT` | Written. Open questions may be unanswered. | The requester answers the open questions. |
| `CONFIRMED` | Every open question is answered. | Building starts. |
| `BUILT` | Merged. Every acceptance line is ticked with the test that proves it. | Record decisions as they come up. |
| `DROPPED` | Not being built. | Nothing. The page stays, with the reason under Decisions. |

## How to write one

- **In this repo, with Claude Code:** run the `capture-intent` skill ([.claude/skills/capture-intent](../.claude/skills/capture-intent/SKILL.md)). It gathers what the codebase already answers, drafts the page, and asks you only the questions a person has to decide.
- **Without the repo** (for example a store owner on claude.ai): paste [TEMPLATE.md](TEMPLATE.md), describe the problem, keep talking until each section is concrete, then commit the result here as a `DRAFT`.

## Rules

- **File name** is the roadmap row in kebab case: `intent/rfq-and-sourcing.md`. One page per shippable item. Check for an existing page before creating one.
- **One page long.** No SQL and no endpoint lists: those belong in the migration and [docs/API-GUIDE.md](../docs/API-GUIDE.md).
- **Keep every section.** Write "none" instead of deleting a heading, so a reviewer can see the section was considered.
- **Keep answered questions.** Append the answer to the question; don't replace it.
- **Name the test in each acceptance line** before building starts.
- **Global rules go in CLAUDE.md as one line.** When a decision is a rule every session must obey, even one that isn't touching this feature, it also gets a single line in [CLAUDE.md](../CLAUDE.md) that links back to this page. The page holds the detail.
- **Bug fixes and small follow-ups don't get a page.** They start from a failing test.
