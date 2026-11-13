# Epic - Automatic evaluation of KYC periodic reviews (i-Hub)

## Business context of the epic

During a KYC periodic review, the bank asks the customer (and its related parties) for documents through i-Hub. While the review is in **Client Communication Ongoing (CCO)** or **Prepare Client Communication**, KYCO follows each review by hand: checks whether documents arrived, decides when to block the customer, unblocks the account, then finalizes the review.

Each review has a **blocking planned date** (communication planned date + 95 days, or NIGO-GO + 45 days). Once it is passed and the review is still incomplete, the customer must be blocked (posting restriction on the account).

This epic automates that follow-up. A scheduled evaluation builds a consolidated view of each review (`ReviewEvaluation`), then applies four business rules:

| Rule | Business meaning | Automatic action | Otherwise |
|---|---|---|---|
| FR 0.0 | i-Hub documents are complete, but non i-Hub blocks still need KYCO | none | task FR 0.0 |
| FR 0.A | Blocking planned date passed and the review is incomplete | review set to `Blocked` | task FR 0.A |
| FR 0.B | Blocked review is complete again | account unblocked | task FR 0.B |
| FR 0.C | Review complete and ready to close | review set to `Remediated` | task FR 0.C |

Low-risk retail customers are processed automatically (straight-through processing). The other profiles produce a Salesforce task so that KYCO keeps control.

**Reference:** `kyc-review-cases.drawio` (Blueprint, How to read, Cases index, Case 1 to 14, Business questions D1 to D7 and Q1 to Q12).

**Already in place:** i-Hub document update service per party (FTKYP-646, FTKYP-643), transactional outbox.

## Ticket overview

| # | Ticket | Depends on | Open questions |
|---|---|---|---|
| 1 | Review Enricher: refresh and consolidate the i-Hub documents of a review | none | none |
| 2 | Review Enricher: non i-Hub blocks, KYC data / KYT, profile, unblock state | 1 | none |
| 3 | Typed task event through the outbox (`KycReviewTaskRequested` + `TaskType`) | none | Q1 to Q4 |
| 4 | Rule FR 0.0: i-Hub documents completed | 2, 3 | none |
| 5 | Rule FR 0.A: blocking | 2, 3 | Q5, Q6, Q7 |
| 6 | Rule FR 0.B: unblocking | 2, 3 | Q10, Q11, Q12 |
| 7 | Rule FR 0.C: validation / finalization (STP) | 2, 3, 6 | Q8, Q9, Q12 |
| 8 | Rule orchestrator and end-to-end tests (14 cases) | 4 to 7 | Q11 |
| 9 | Review selection and scheduling (later) | 8 | to define |

Tickets 1 and 3 can start in parallel on day one.

---

## Ticket 1 - Review Enricher: refresh and consolidate the i-Hub documents of a review

**Type:** Technical story
**Reuses:** FTKYP-646 (i-Hub document update service per party), FTKYP-643 (iHub adapter `GET /v1/bil-parties/{partyId}/completeness-status`)

### Business context
During a periodic review, the customer and its related parties send documents through i-Hub. The i-Hub back office validates them. Today KYCO opens every member of the review and checks in i-Hub where each document stands. The review can only move forward when **all** i-Hub document blocks of **all** members are compliant (validated by system or by user).

Three situations matter for the business rules:
- a document is **pending**: the customer provided it and the back office is validating it. Blocking is paused while it is pending (decision D2, case 7).
- a document is **rejected**: the back office refused it. Automatic blocking is not allowed, KYCO must look at it (case 8).
- a block **has just been validated**: KYCO must be told that the i-Hub part is now complete (rule FR 0.0, case 3). This is an event of the current execution, not a state.

### Technical description
FTKYP-646 already refreshes the i-Hub documents of **one party**: it calls i-Hub, updates the document statuses and the review blocks, and returns four indicators. This ticket calls it for **every active member of a review** and consolidates the four indicators at review level into `ReviewEvaluation`. No document logic is reimplemented here.

Review selection and scheduling are out of scope (ticket 9): the input of this ticket is one review id.

### Processing
1. **Load the review members**
   - Main party and related parties of the review.
   - Keep members with status **different from `removed`**.
   - Keep only members that have at least one i-Hub document block on the review (`DOCS_IHUB_COMMON` or `DOCS_IHUB_SPECIFIC`).
2. **Call FTKYP-646 for each member**
   - Input: `partyId` and the list of i-Hub block types the member has on the review.
   - FTKYP-646 updates documents and blocks (all documents `IN_GOOD_ORDER` moves the block to `VALIDATED_BY_SYSTEM`, otherwise the block keeps its status). Locked or finalized blocks are not updated.
   - Output per member:

```json
{
  "newValidation": true,
  "allBlocksCompliant": false,
  "rejected": true,
  "pending": true
}
```

3. **Consolidate at review level**

| Review indicator | Rule | Meaning |
|---|---|---|
| `newValidation` | OR of all members | at least one block of the review was validated during this execution |
| `allBlocksCompliant` | AND of all members | every i-Hub block of every member is validated (by system or by user) |
| `pending` | OR of all members | at least one document under back-office validation |
| `rejected` | OR of all members | at least one document rejected |

4. **Expose the result in `ReviewEvaluation`**

```java
public record ReviewEvaluation(
    String reviewId,
    ReviewStatus status,             // CCO, PREPARE_COM, BLOCKED
    LocalDate blockingPlannedDate,
    ReviewDocumentResult documents
    // completed by ticket 2
) {}

public record ReviewDocumentResult(
    boolean newValidation,
    boolean allBlocksCompliant,
    boolean pending,
    boolean rejected
) {}
```

### Examples (review with two members)

| Member A | Member B | `newValidation` | `allBlocksCompliant` | `pending` | `rejected` |
|---|---|---|---|---|---|
| block just validated, compliant | already compliant | true | true | false | false |
| already compliant | already compliant | false | true | false | false |
| block just validated, compliant | one document pending | true | false | true | false |
| one document rejected | one document pending | false | false | true | true |
| no change, not compliant | already compliant | false | false | false | false |

### Edge cases
- **No eligible member** (all removed or no i-Hub block): `allBlocksCompliant = true`, all other indicators false, warning logged. To confirm within the team: a review without i-Hub block is compliant on the i-Hub side.
- **FTKYP-646 fails for one member** (iHub adapter down, timeout): the review evaluation stops, error logged with review id and party id, no rule is applied for this review in this execution.
- **Loss of `newValidation`**: FTKYP-646 persists its updates immediately. If member A gets `newValidation = true` and the evaluation then fails on member B, the next execution sees no change on A and FR 0.0 is never triggered. Options to agree within the team:
  - run the refresh and the rules in the same transaction, so a failure rolls back the document updates too,
  - or persist a "validation not yet evaluated" flag per review, cleared once the rules have run.
- **Locked or finalized blocks**: not updated by FTKYP-646, their current compliance is taken as is.

### Out of scope
- Document update logic, i-Hub call and block transition (FTKYP-646, FTKYP-643).
- Non i-Hub blocks, KYC data / KYT, profile (ticket 2).
- Review selection and scheduling (ticket 9).

### Acceptance criteria
- Given a review with a `removed` member, when the enricher runs, then FTKYP-646 is not called for that member.
- Given a member with no i-Hub block on the review, then FTKYP-646 is not called for that member.
- Given a member with only `DOCS_IHUB_SPECIFIC`, then FTKYP-646 is called with that block type only.
- Given two members both compliant after the call, then `allBlocksCompliant = true`.
- Given one member not compliant, then `allBlocksCompliant = false`.
- Given one member with `newValidation = true`, then the review `newValidation = true`.
- Given one member with `pending = true`, then the review `pending = true`.
- Given one member with `rejected = true`, then the review `rejected = true`.
- Given FTKYP-646 fails for one member, then the error is logged with review id and party id and no rule runs for this review.
- Given the examples table above, then every line returns the expected indicators.

### Tests
- Unit tests on the consolidation (OR / AND) with FTKYP-646 mocked, one test per line of the examples table.
- Integration test: review with a removed member and a member without i-Hub block.
- Draw.io cases covered (document part): 3, 6, 7, 8, 9.

---

## Ticket 2 - Review Enricher: non i-Hub blocks, KYC data / KYT, profile and unblock state

**Type:** Technical story

### Business context
Documents are not the only part of a review. KYCO also checks:
- the **non i-Hub blocks** (manual analysis blocks). An **amber** block means KYCO still has manual work to do. A review cannot be unblocked or finalized while one is amber, and FR 0.0 only alerts KYCO when one is amber.
- the **KYC data and KYT assessment** of the main party and of the review. If they are KO at the blocking planned date, the customer must be blocked even if documents are fine (case 6).

The **customer profile** decides whether the bank accepts an automatic action or wants a human decision:
- automatic blocking: party tiering 4 or 5, pool Retail, AML score Low, no rejected document.
- automatic unblocking: pool Retail, AML score Low.
- automatic finalization: pool Retail, AML score Low, main DAO not 831 Retail International.

Finally, the account unblocking is a **process action** that is marked processed once done and is never relaunched (decision D3). Rules need its state to know whether a Blocked review can be finalized.

### Technical description
Complete `ReviewEvaluation` (ticket 1) with every remaining criterion, so that rules never call another service.

```java
public record ReviewEvaluation(
    String reviewId,
    ReviewStatus status,
    LocalDate blockingPlannedDate,
    ReviewDocumentResult documents,      // ticket 1
    boolean nonIhubBlocksOk,             // every non i-Hub block is green or grey
    boolean kycDataAndKytOk,             // KYC data and KYT OK for main party and review
    CustomerProfile profile,
    UnblockActionState unblockAction     // NONE, PENDING, PROCESSED, ERROR
) {}

public record CustomerProfile(
    String partyTiering,   // "1" to "5"
    String pool,           // "Retail", ...
    String amlScore,       // "Low", ...
    String mainDao         // "831" = Retail International
) {
    public boolean autoBlockingAllowed()   { /* tiering 4 or 5 + Retail + Low */ }
    public boolean autoUnblockingAllowed() { /* Retail + Low */ }
    public boolean autoFinalizationAllowed() { /* Retail + Low + main DAO != 831 */ }
}
```

### Scope
1. **Non i-Hub blocks:** read the status of every non i-Hub block of the review and its members. `nonIhubBlocksOk = false` as soon as one is amber.
2. **KYC data / KYT:** read the KYC data status and the KYT assessment of the main party and of the review. `kycDataAndKytOk = false` as soon as one is KO.
3. **Profile:** read tiering, pool, AML score and main DAO. Put the three "auto allowed" checks in `CustomerProfile`, so the rule tickets do not duplicate them.
4. **Unblock action state:** read the state of the unblock process action of the review.

### Edge cases
- No non i-Hub block on the review: `nonIhubBlocksOk = true`.
- Missing KYC data or KYT assessment: treated as KO, logged.
- Missing profile field: no automatic action allowed (all three checks return false), logged.
- Unblock action in `ERROR`: exposed as is, rules decide (case 11, open question Q10).

### Out of scope
- i-Hub documents (ticket 1).
- Any decision: this ticket only reads and exposes data.

### Acceptance criteria
- Given one non i-Hub block in amber, then `nonIhubBlocksOk = false`.
- Given all non i-Hub blocks green or grey, then `nonIhubBlocksOk = true`.
- Given KYT KO on the main party, then `kycDataAndKytOk = false`.
- Given tiering 4, Retail, Low, then `autoBlockingAllowed() = true`.
- Given tiering 3, Retail, Low, then `autoBlockingAllowed() = false` and `autoUnblockingAllowed() = true`.
- Given main DAO 831, Retail, Low, then `autoUnblockingAllowed() = true` and `autoFinalizationAllowed() = false`.
- Given a missing AML score, then all three checks return false.
- Given an unblock action marked processed, then `unblockAction = PROCESSED`.

### Tests
- Unit tests on the three `CustomerProfile` checks (full truth table).
- Draw.io cases covered: 3, 6, 9, 10, 14 (context part).

---

## Ticket 3 - Typed task event through the outbox: `KycReviewTaskRequested` and `TaskType`

**Type:** Technical story

### Business context
When a rule cannot act automatically, KYCO must receive a **Salesforce task** telling what to do on the review. There are four kinds of tasks, one per rule. KYCO must never receive the same task twice for the same review: an existing task is **reopened and updated**, not duplicated (decision D7). A task must never be lost, even if Salesforce is unavailable.

### Technical description
The outbox is already in place. All four rules publish **one single typed event**, `KycReviewTaskRequested`. One handler consumes it and creates or updates the Salesforce task. The whole task configuration lives in the `TaskType` enum.

```java
public record KycReviewTaskRequested(
    UUID eventId,        // outbox idempotency
    String reviewId,
    TaskType taskType,
    Instant occurredAt
) {}

public enum TaskType {
    FR_0_0("KYC REVIEW - i-HUB documents complete. Ready for follow-up",
           "<description>", "<queue>", Priority.NORMAL),
    FR_0_A("KYC REVIEW - The customer eligible for blocking",
           "<description>", "<queue>", Priority.HIGH),
    FR_0_B("KYC REVIEW - Restrictions of the customer to be manually lifted",
           "<description>", "<queue>", Priority.HIGH),
    FR_0_C("KYC REVIEW - The review meets the criteria for a finalization",
           "<description>", "<queue>", Priority.NORMAL);

    private final String subject;
    private final String description;
    private final String queue;
    private final Priority priority;
    // constructor + getters
}
```

Descriptions, queues and priorities are placeholders, to be provided by the business. Changing a value requires a release.

### Scope
1. Event record and `TaskType` enum.
2. Publication through the existing outbox, **in the same transaction** as the rule evaluation (status change and event are committed together or not at all).
3. One handler `KycReviewTaskHandler`:
   - reads subject, description, queue and priority from `TaskType`,
   - looks for an existing Salesforce task with the key `reviewId + taskType`,
   - absent: create. Open: update. Closed: reopen and update.
4. Idempotency on `eventId`: the same event delivered twice is processed once.
5. Errors:
   - unknown task type in the payload: event in error, logged with review id, not lost.
   - Salesforce unavailable: retry by the outbox mechanism.

### Out of scope
- The rules (tickets 4 to 7).
- Events on status transitions (Blocked, Remediated), if other modules need them later.

### Acceptance criteria
- Given a rule publishes the event, then it is stored in the outbox in the same transaction as the rule result.
- Given a rollback of the rule transaction, then no event is stored.
- Given no existing task for this review and type, then one task is created with the subject, description, queue and priority of its `TaskType`.
- Given an open task of the same type, then the existing task is updated, no duplicate.
- Given a closed task of the same type, then the task is reopened and updated.
- Given the same `eventId` delivered twice, then it is processed only once.
- Given an unknown task type, then the event goes to error and is logged with the review id.
- Given Salesforce unavailable, then the event is retried and the task is created once Salesforce is back.

### Open questions
- Q1: uniqueness key `review + task type`, correct on the Salesforce side?
- Q2: open task and nothing changed, update on every evaluation or only on change?
- Q3: task closed by KYCO without doing the action, reopen it?
- Q4: task open for a long time, reminder after X days?

---

## Ticket 4 - Rule FR 0.0: i-Hub documents completed

**Type:** Technical story

### Business context
When the last i-Hub document of a review is validated, the i-Hub part is done. If KYCO still has **manual analysis** to do on non i-Hub blocks (at least one amber), KYCO must be told that it can now continue with those blocks. If all non i-Hub blocks are already OK, there is nothing to tell: the other rules take over.

This rule never changes the review, it only informs KYCO. It has **no condition on the blocking planned date** (decision D1), so it can fire in the same evaluation as FR 0.A (case 6) and on a Blocked review (case 9).

### Rule

```
eligible if
    status in (CCO, PREPARE_COM, BLOCKED)
    and documents.allBlocksCompliant
    and documents.newValidation
    and not nonIhubBlocksOk
then
    publish KycReviewTaskRequested(FR_0_0)
```

No automatic action.

### Technical description
- Implement as `Fr00DocumentsCompletedRule implements ReviewRule`.
- `ReviewRule` contract (shared by tickets 4 to 7, created in this ticket if not existing):

```java
public interface ReviewRule {
    RuleOutcome evaluate(ReviewEvaluation evaluation);
}

public sealed interface RuleOutcome {
    record NotApplicable() implements RuleOutcome {}
    record AutomaticAction(ReviewAction action) implements RuleOutcome {}
    record TaskRequested(TaskType type) implements RuleOutcome {}
}
```

- The rule is a pure function of `ReviewEvaluation`: no repository, no external call. Side effects are applied by the orchestrator (ticket 8).

### Acceptance criteria
- Given docs all valid, one block just validated, one non i-Hub block amber, status CCO, then `TaskRequested(FR_0_0)`. (Case 3)
- Given the same with status Blocked, then `TaskRequested(FR_0_0)`. (Case 9)
- Given the same after the blocking planned date, then `TaskRequested(FR_0_0)`. (Case 6)
- Given all non i-Hub blocks OK, then `NotApplicable`.
- Given no block just validated, then `NotApplicable`.
- Given status Remediated, then `NotApplicable`.

### Tests
Unit tests on every condition. Draw.io cases: 3, 6, 9.

---

## Ticket 5 - Rule FR 0.A: blocking

**Type:** Technical story

### Business context
The customer had until the **blocking planned date** to provide what was asked. Once it is passed and the review is still incomplete (documents not valid, or KYC data / KYT KO), the customer must be **blocked**.

- **Low-risk customers** (tiering 4 or 5, Retail, AML Low) are blocked automatically, unless a document was **rejected**: a rejection needs a human look (case 8).
- **Other customers**: KYCO receives a task and decides.
- If a document is **pending** (provided by the customer, under back-office validation), blocking is **paused**: the customer did the job, the bank is late (decision D2, case 7). Once the back office decides, the rule applies normally.

### Rule

```
eligible if
    status in (CCO, PREPARE_COM)
    and blockingPlannedDate < today
    and not documents.pending
    and (not documents.allBlocksCompliant or not kycDataAndKytOk)
then
    if profile.autoBlockingAllowed() and not documents.rejected
        AutomaticAction(BLOCK_REVIEW)        -> status BLOCKED
    else
        TaskRequested(FR_0_A)
```

### Technical description
- `Fr0ABlockingRule implements ReviewRule`.
- `BLOCK_REVIEW` is applied by the orchestrator (ticket 8): status transition to `Blocked`, then the existing blocking process (posting restriction) if it is triggered by the status. To confirm with the team how the account restriction is applied today.
- "today" must come from an injected `Clock`, for testability.

### Acceptance criteria
- Given an eligible review, tiering 4, Retail, Low, no rejected document, then `AutomaticAction(BLOCK_REVIEW)`. (Case 4)
- Given an eligible review, tiering 2, then `TaskRequested(FR_0_A)`. (Case 5)
- Given an eligible review with an auto profile but a rejected document, then `TaskRequested(FR_0_A)`. (Case 8)
- Given documents valid but KYC data / KYT KO, then the rule applies. (Case 6)
- Given a pending document, then `NotApplicable`. (Case 7)
- Given that document rejected at a later evaluation, then `TaskRequested(FR_0_A)`. (Case 7)
- Given the blocking planned date equal to today or later, then `NotApplicable`.
- Given status Blocked, then `NotApplicable`.

### Open questions
- Q5: is there a back-office SLA after which KYCO must be alerted on a pending document?
- Q6: only non i-Hub blocks KO (documents and KYC OK): no blocking, intended?
- Q7: tiering "4 and 5" means "4 or 5"?

### Tests
Unit tests with a fixed `Clock`. Draw.io cases: 4, 5, 6, 7, 8.

---

## Ticket 6 - Rule FR 0.B: unblocking

**Type:** Technical story

### Business context
A Blocked customer who provided everything must get access to the account again. Unblocking means **removing the posting restriction**. It is done through a **process action**, marked processed once done and never relaunched (decision D3).

FR 0.B **only unblocks the account**. The review stays `Blocked`: finalizing it is the job of FR 0.C (decision D4). This keeps two separate tasks for KYCO: one to lift the restrictions, one to finalize.

- **Retail, AML Low** (831 included): automatic unblock.
- **Other customers**: KYCO receives a task FR 0.B and unblocks by hand.
- No unblock while a non i-Hub block is still amber (case 9).

### Rule

```
eligible if
    status = BLOCKED
    and documents.allBlocksCompliant
    and nonIhubBlocksOk
    and unblockAction != PROCESSED
    [and kycDataAndKytOk  -> to confirm, Q12]
then
    if profile.autoUnblockingAllowed()
        AutomaticAction(TRIGGER_UNBLOCK)     -> unblock process action
    else
        TaskRequested(FR_0_B)
```

### Technical description
- `Fr0BUnblockingRule implements ReviewRule`.
- `TRIGGER_UNBLOCK` is applied by the orchestrator: create or trigger the unblock process action. The review status is **not** changed.
- When `unblockAction = PENDING` (triggered but not yet processed), the rule returns `NotApplicable` to avoid a second trigger.
- When `unblockAction = ERROR`, behaviour depends on Q10. Default proposal: `NotApplicable` and log, no automatic retry by this rule.

### Acceptance criteria
- Given a Blocked review, all OK, Retail, Low, not 831, then `AutomaticAction(TRIGGER_UNBLOCK)`. (Case 4)
- Given the same with main DAO 831, then `AutomaticAction(TRIGGER_UNBLOCK)`. (Case 10)
- Given a Blocked review, all OK, not Retail or not Low, then `TaskRequested(FR_0_B)`. (Case 5)
- Given `unblockAction = PROCESSED`, then `NotApplicable`.
- Given `unblockAction = PENDING`, then `NotApplicable`.
- Given one non i-Hub block amber, then `NotApplicable`. (Case 9)
- Given status CCO, then `NotApplicable`.
- In every case the rule never returns a status change.

### Open questions
- Q10: unblock action in error, retry or alert KYCO after N failures? (case 11)
- Q11: document invalid again after the unblock, who re-blocks? (case 12)
- Q12: add the KYC data / KYT check? Without it, a review blocked only for KYC would be unblocked with KYC still KO. (case 14)

### Tests
Unit tests on every condition and every `UnblockActionState`. Draw.io cases: 4, 5, 9, 10, 11.

---

## Ticket 7 - Rule FR 0.C: validation / finalization (STP)

**Type:** Technical story

### Business context
A review is ready to close when every document is valid and every non i-Hub block is OK. Two paths lead there:
- the review was **never blocked**: it is in CCO and the blocking planned date is passed (cases 1 and 2),
- the review was **blocked and its account is now unblocked**: unblock action processed (cases 4, 5, 10, 13).

**Low-risk retail customers outside Retail International (831)** are finalized automatically: the review becomes `Remediated` (straight-through processing). For the others, KYCO receives a task FR 0.C and finalizes by hand (decision D6). For 831, this means the account is unblocked automatically but the review stays Blocked until KYCO finalizes (case 10, Q9).

### Rule

```
eligible if
    documents.allBlocksCompliant
    and nonIhubBlocksOk
    [and kycDataAndKytOk  -> to confirm, Q12]
    and (
        (status = CCO and blockingPlannedDate < today)
     or (status = BLOCKED and unblockAction = PROCESSED)
    )
then
    if profile.autoFinalizationAllowed()
        AutomaticAction(REMEDIATE)           -> status REMEDIATED
    else
        TaskRequested(FR_0_C)
```

### Technical description
- `Fr0CFinalizationRule implements ReviewRule`.
- `REMEDIATE` is applied by the orchestrator: status transition to `Remediated`.
- Must run **after** FR 0.B in the same evaluation (ticket 8). If the unblock process action is processed synchronously, a low-risk blocked review is finalized in one evaluation; otherwise at the next one.
- Never returns `REMEDIATE` while `unblockAction` is `PENDING` or `ERROR` (case 11).

### Acceptance criteria
- Given CCO, date passed, all OK, Retail, Low, not 831, then `AutomaticAction(REMEDIATE)`. (Case 1)
- Given the same with main DAO 831, then `TaskRequested(FR_0_C)`. (Case 2)
- Given Blocked, unblock processed, auto profile, then `AutomaticAction(REMEDIATE)`. (Case 4)
- Given Blocked, unblock processed, 831, then `TaskRequested(FR_0_C)`. (Case 10)
- Given Blocked, unblock processed manually by KYCO, not Retail or not Low, then `TaskRequested(FR_0_C)`. (Cases 5 and 13)
- Given Blocked, unblock `PENDING` or `ERROR`, then `NotApplicable`. (Case 11)
- Given CCO, date not reached, then `NotApplicable`.
- Given one non i-Hub block amber, then `NotApplicable`.

### Open questions
- Q8: why wait for the blocking planned date to finalize a review that is complete before? (case 1)
- Q9: 831, account unblocked automatically, review Blocked until KYCO finalizes. Confirm? (case 10)
- Q12: add the KYC data / KYT check? (case 14)

### Tests
Unit tests on both eligibility paths. Draw.io cases: 1, 2, 4, 5, 10, 11, 13.

---

## Ticket 8 - Rule orchestrator and end-to-end tests

**Type:** Technical story

### Business context
For a given review, the four rules must be applied in a **fixed and predictable order**, so that the result is the same whatever happens, and so that KYCO never receives contradictory tasks. The 14 cases of the draw.io file are the reference agreed with the business: each one becomes an automated test.

### Technical description
`ReviewEvaluationService.evaluate(reviewId)`:
1. Build `ReviewEvaluation` (tickets 1 and 2).
2. Run the rules in this order: **FR 0.0, FR 0.A, FR 0.B, FR 0.C**.
3. Apply each outcome in **one transaction** per review:
   - `AutomaticAction`: status transition or unblock process action,
   - `TaskRequested`: publish `KycReviewTaskRequested` through the outbox (ticket 3).
4. After FR 0.B returns `TRIGGER_UNBLOCK`, reload the unblock state before FR 0.C, so that a synchronously processed unblock is finalized in the same evaluation.
5. Return a `ReviewEvaluationReport` (review id, outcomes per rule) for logging and audit.

### Combination rules
- FR 0.0 and FR 0.A can both produce an outcome in the same evaluation (case 6).
- FR 0.A and FR 0.B / FR 0.C are mutually exclusive by status (CCO or Prepare com vs Blocked).
- FR 0.B and FR 0.C can both act in the same evaluation on a low-risk blocked review (case 4).
- One failing review: rollback for that review only, error logged, the report is marked failed.

### Acceptance criteria
- Given Case 4 inputs, then the review goes Blocked, unblock processed, then Remediated.
- Given Case 6 inputs, then both FR 0.0 and FR 0.A outcomes are produced.
- Given a failure while applying FR 0.A, then no status change and no event are stored for that review.
- One integration test per draw.io case, Case 1 to Case 14. Conflict cases 12 and 14 are written once the business has answered Q11 and Q12.
- All case tests pass in CI.

### Open question
- Q11: document invalid again while the review is Blocked and the account already open: who re-blocks? (case 12)

---

## Ticket 9 - Review selection and scheduling (later)

**Type:** Technical story, to detail later

### Business context
Every open review must be re-evaluated regularly, so that a document validated, a date passed or a KYCO action is taken into account without manual intervention.

### Outline
- Select reviews with status `Client Communication Ongoing`, `Prepare Client Communication` or `Blocked`.
- Call `ReviewEvaluationService.evaluate(reviewId)` for each one. One failing review does not stop the others.
- Frequency, batching, locking (no parallel evaluation of the same review) and monitoring: to define.

### Acceptance criteria
To write when the ticket is refined.
