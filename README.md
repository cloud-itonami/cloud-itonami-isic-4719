# cloud-itonami-isic-4719

Open Business Blueprint for **ISIC Rev.5 4719**: other retail sale in
non-specialized stores -- department stores and general-merchandise
stores without predominant food sales (distinct from sibling ISIC 4711's
predominantly-food community retail).

This repository publishes a general-merchandise-retail
operations-COORDINATION actor -- sales/inventory/return transaction
logging, floor-staff scheduling, merchandise supply-order coordination
with registered vendors, and loss-prevention-concern flagging -- as an
OSS business that any qualified operator can fork, deploy, run, improve
and sell, so an independent department/general-merchandise store never
surrenders its operations data to a closed back-office SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **MerchandiseRetailAdvisor
⊣ MerchandiseRetailGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:merchandise-retail-governor`, is
a distinct, independent build (no naming-collision precedent question --
distinct from ISIC 4711's own `:retail-governor` and ISIC 5610's own
`:food-service-governor`).

> **Why an actor layer at all?** An LLM is great at drafting a sales-
> record summary, a staffing proposal, or a supply-order request -- but
> it has no license to actually finalize a loss-prevention-enforcement
> action against a customer, no way to independently confirm a store or
> a supply-order vendor is actually a registered/verified counterparty,
> and no notion of when a "flag this concern" op quietly turns into a
> claim to have already acted on it. Letting it act directly invites an
> unverified store's data entering the ledger, an unverified vendor
> receiving a merchandise order, or -- worst of all -- a fabricated claim
> to have detained or searched a suspected shoplifter, exposing the shop
> and its staff to real liability. This project seals the
> MerchandiseRetailAdvisor into a single node and wraps it with an
> independent **MerchandiseRetailGovernor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: coordination only, not enforcement

This actor is **operations coordination only**. It never performs or
authorizes:

- setting or overriding a shelf/unit price
- directly finalizing a loss-prevention-enforcement action (detaining,
  searching, arresting, or otherwise physically restraining a suspect;
  confiscating a suspect's belongings)
- loss-prevention-authority enforcement (instructing security to
  physically intervene, pressing charges)

The governor's `scope-exclusion-violations` check re-scans every
proposal for this failure mode independently of the advisor's own
framing, and treats it as a HARD, permanent block regardless of
confidence or how clean everything else is. Flagging a loss-prevention
concern for a human to triage is exactly this actor's job --
`:flag-loss-prevention-concern` is never excluded by this check, only
FINALIZING/enforcing/physically-acting-on that concern is.

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** Two independent layers enforce this
(`merchandiseops.governor`'s `effect-not-propose-violations` HARD check
and `merchandiseops.phase`'s phase table, which never puts
`:flag-loss-prevention-concern` in any phase's `:auto` set). A human
store operator/loss-prevention coordinator is always the one who
actually acts on a flagged concern or confirms a high-cost supply order.

## The core contract

```
store/vendor registration + operations-coordination request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────┐
   │ MerchandiseRetail-    │ ─────────────▶ │ MerchandiseRetailGovernor   │  (independent system)
   │ Advisor (sealed)      │  + citations    │ store-unverified ·          │
   └───────────────────────┘                 │ vendor-unverified (NEW) ·   │
          │                 commit ◀┼ effect-not-propose ·               │
          │                         │ scope-excluded (loss-prevention-    │
    record + ledger        escalate ┼ enforcement finalization) ·         │
          │              (ALWAYS for│ op-not-allowed                      │
          │       :flag-loss-       │                                      │
          │       prevention-       └────────────────────────────┘
          │       concern/high-cost
          │       supply-order)
          ▼
      human approval
```

**The MerchandiseRetailAdvisor never commits a proposal the
MerchandiseRetailGovernor would reject, and a loss-prevention-concern
flag or a high-cost supply order never commits without a human
sign-off.** Hard violations (an unregistered/unverified store; an
unregistered/unverified supply-order vendor; a non-`:propose` effect;
content touching loss-prevention-enforcement finalization; an op outside
the closed allowlist) force **hold** and *cannot* be approved past.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
may perform physical domain work** (here: shelfing, picking, restocking,
point-of-sale handling) under human/robot floor operations gated by store
policy. This actor itself does not dispatch robot/hardware actions -- it
is strictly the operations-coordination layer (sales-record logging,
staffing scheduling, supply-order coordination, loss-prevention-concern
flagging) any physical-dispatch layer could eventually feed proposals
into, always gated the same way by the independent
MerchandiseRetailGovernor.

## Features

- **Closed proposal-op allowlist**: `log-sales-record`,
  `schedule-staffing-operation`, `coordinate-supply-order`,
  `flag-loss-prevention-concern` (all `:effect :propose`).
- **Four HARD governor checks** (permanent, un-overridable):
  1. **Store unverified** -- the target store's business registration
     must exist AND be independently registered/verified in the store.
  2. **Vendor unverified** (FLAGSHIP NEW) -- for `:coordinate-supply-
     order` only, the named vendor must exist AND be independently
     registered/verified -- a supply-chain counterparty-verification
     gate no sibling 47xx actor has had reason to add.
  3. **Effect is :propose** -- any other `:effect` value is rejected.
  4. **Scope exclusion** -- directly finalizing a loss-prevention-
     enforcement action (detention, search, arrest, confiscation) and an
     op outside the closed allowlist are both permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-loss-prevention-concern` -- ALWAYS escalates, regardless of
    confidence or phase. A "flag a concern" op is never auto-commit
    eligible and never finalizes a loss-prevention-enforcement decision
    itself -- it only surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold -- a large-value
    procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: sales-record logging only (approval-gated)
  - Phase 2: + staffing-operation scheduling, supply-order proposals
    (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (loss-prevention concerns and high-cost supply orders always
    escalate)
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

### Test suite

- `test/merchandiseops/governor_test.clj` -- unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/merchandiseops/advisor_test.clj` -- advisor proposal shape and
  consistency
- `test/merchandiseops/phase_test.clj` -- rollout phase logic
- `test/merchandiseops/governor_contract_test.clj` -- full graph
  integration, audit trail
- `test/merchandiseops/store_contract_test.clj` -- Store protocol and
  MemStore implementation

### Modules

- `merchandiseops.store` -- SSoT (MemStore, String-keyed store/vendor
  directories, append-only ledger)
- `merchandiseops.advisor` -- contained intelligence node (mock +
  real-LLM seam)
- `merchandiseops.governor` -- independent compliance layer
- `merchandiseops.phase` -- staged rollout (0→3)
- `merchandiseops.operation` -- langgraph-clj StateGraph
- `merchandiseops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4719`).

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Sales/inventory/return transaction logging (`:log-sales-record`) | Real POS/inventory-system integration |
| Floor-staff scheduling coordination (`:schedule-staffing-operation`) | Direct staff time-clock/payroll integration |
| Merchandise supply-order coordination with a registered, verified vendor, HARD-gated on vendor verification and a double-actuation-free single-proposal shape (`:coordinate-supply-order`) | Real supplier-ordering-system integration |
| Loss-prevention-concern flagging, ALWAYS human-gated (`:flag-loss-prevention-concern`) | Directly finalizing any loss-prevention-enforcement action -- permanently out of scope, not a gap |
| Immutable audit ledger for every log/schedule/order/flag decision | Daily reconciliation/cash-up -- a follow-up slice, not in this R0 |

Extending coverage is additive: add the next op (e.g. a return-
authorization or a cash-discrepancy-escalation check) as its own
governed op with its own HARD checks and tests, following the SAME "an
independent governor re-verifies against the actor's own records before
any real-world act" pattern this repo's flagship checks already
establish.

## Maturity

`:implemented` -- `MerchandiseRetailAdvisor` + `MerchandiseRetailGovernor`
run as real, tested code (see `Development` above), following the SAME
governed-actor architecture as every prior actor across this fleet, with
its own distinct, independently-named governor and its own novel
supply-chain vendor-verification check.

## License

Code and implementation templates are AGPL-3.0-or-later.
