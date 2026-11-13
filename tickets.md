# Epic - Automatic evaluation of KYC periodic reviews (i-Hub)

## Business context of the epic

During a KYC periodic review, the bank asks the customer (and its related parties) for documents through i-Hub. While the review is in **Client Communication Ongoing (CCO)** or **Prepare Client Communication**, KYCO follows each review by hand: checks whether documents arrived, decides when to block the customer, unblocks the account, then finalizes the review.

Each review has a **blocking planned date** (communication planned date + 95 days, or NIGO-GO + 45 days). Once it is passed and the review is still incomplete, the customer must be blocked (posting restriction on the account).

This epic automates that follow-up. For each review, the system builds a consolidated view (`ReviewContext`), then applies four business rules:

| Rule | Business meaning | Automatic action | Otherwise |
|---|---|---|---|
| FR 0.0 | i-Hub documents are complete, but non i-Hub blocks still need KYCO | none | task FR 0.0 |
| FR 0.A | Blocking planned date passed and the review is incomplete | review set to `Blocked` | task FR 0.A |
| FR 0.B | Blocked review is complete again | account unblocked (process action) | task FR 0.B |
| FR 0.C | Review complete and ready to close | review set to `Remediated` | task FR 0.C |

Low-risk retail customers are processed automatically. The other profiles produce a Salesforce task so that KYCO keeps control.

**Already in place:** i-Hub document update service per party (FTKYP-646, FTKYP-643), process actions on the review, outbox, Spring Modulith.
**In progress in another ticket:** posting restriction adapter (put and remove posting restrictions on an account).

## Ticket overview

| # | Block | Ticket | Depends on |
|---|---|---|---|
| 1 | Data | Review documents evaluation | none |
| 2 | Data | Review Context (facts only, rules decide) | 1, 5 |
| 3 | Foundation | KYC review task: Spring Modulith event and Salesforce listener | none |
| 4 | Foundation | Rule framework and rule outcome executor | 3, 5 |
| 5 | Unblocking | Process action `UNBLOCKING_ACCOUNT`: type, init, lifecycle, backfill | none |
| 6 | Unblocking | Unblocking account execution: listener, outbox, adapter call | 5, adapter ticket |
| 7 | Blocking | Migrate account blocking to the posting restriction adapter | adapter ticket |
| 8 | Rules | FR 0.0 Documents completed | 2, 4 |
| 9 | Rules | FR 0.A Blocking | 2, 4 |
| 10 | Rules | FR 0.B Unblocking | 2, 4, 5 |
| 11 | Rules | FR 0.C Validation STP | 2, 4, 5 |
| 12 | Assembly | Rule orchestrator and end-to-end tests | 8 to 11 |
| 13 | Assembly | Review selection and scheduling (later) | 12 |

```
Day 1, in parallel:  [1 Documents]   [3 Task event]   [5 Unblock action]   [7 Blocking migration]
                          |               |                |      \
                     [2 Context]   [4 Framework + Executor] [6 Unblock execution]
                            \            /
              [8 FR 0.0] [9 FR 0.A] [10 FR 0.B] [11 FR 0.C]      (in parallel)
                                  |
                          [12 Orchestrator]
                                  |
                          [13 Scheduling]
```

---

## Ticket 1 - Review documents evaluation

**Type:** Technical story
**Reuses:** FTKYP-646 (i-Hub document update service per party), FTKYP-643 (iHub adapter `GET /v1/bil-parties/{partyId}/completeness-status`)

### Business context
A review can only move forward when **all** i-Hub documents of **all** its members (main party and related parties) are validated. Today KYCO checks every member one by one in i-Hub. This service does it for the whole review in one call: it refreshes the documents of each member and gives one answer for the review.

### Technical description
Input: the `Review` (loaded by the caller, ticket 2).

1. **Get the members of the review**
   - Main party and related parties with status **different from `removed`**.
   - Keep only members that have at least one i-Hub document block on the review (`DOCS_IHUB_COMMON` or `DOCS_IHUB_SPECIFIC`).
2. **Call FTKYP-646 for each member**
   - Input: `partyId` and the i-Hub block types of the member on the review.
   - FTKYP-646 calls i-Hub, updates the documents and the blocks, and returns:

```json
{ "newValidation": true, "allBlocksCompliant": false, "rejected": true, "pending": true }
```

3. **Consolidate at review level**

| Indicator | Calculation | Meaning |
|---|---|---|
| `newValidation` | OR of all members | at least one block validated during this execution |
| `allBlocksCompliant` | AND of all members | every i-Hub block of every member validated (by system or by user) |
| `pending` | OR of all members | at least one document under back-office validation |
| `rejected` | OR of all members | at least one document rejected |

```java
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
- **No member with an i-Hub block:** `allBlocksCompliant = true`, other indicators false, warning logged. To confirm within the team.
- **FTKYP-646 fails for one member:** the service fails, error logged with review id and party id.
- **Loss of `newValidation`:** FTKYP-646 compares the block before and after **its own call**. If the block was validated and committed at run N but the evaluation failed, run N+1 sees the block already valid and `newValidation = false`: the movement is lost. The detection must rely on a persisted state, not on the last call. Two options, to decide within the team:
  - **A. Flag set by FTKYP-646:** when a block passes to `VALIDATED_BY_SYSTEM`, FTKYP-646 sets `documentsValidatedToEvaluate = true` on the review in the same commit. The evaluation resets it only in its final transaction.
  - **B. Timestamp (no change to FTKYP-646):** `newValidation = at least one block with validatedAt > review.documentsLastEvaluatedAt`. The evaluation updates `documentsLastEvaluatedAt` in its final transaction. Requires a reliable validation date on the blocks.

### Acceptance criteria
- Given a member with status `removed`, then FTKYP-646 is not called for it.
- Given a member without i-Hub block on the review, then FTKYP-646 is not called for it.
- Given a member with only `DOCS_IHUB_SPECIFIC`, then FTKYP-646 is called with that block type only.
- Given two members compliant, then `allBlocksCompliant = true`; one not compliant, then `false`.
- Given one member with `newValidation`, `pending` or `rejected` true, then the review indicator is true.
- Given FTKYP-646 fails, then the error is logged with review id and party id.
- Every line of the examples table returns the expected indicators.

---

## Ticket 2 - Review Context: entry point of the review evaluation

**Type:** Technical story
**Uses:** ticket 1, ticket 5 (unblocking process action), existing service exposing KYC data, KYT and non i-Hub blocks (name to complete), party tiering API

### Business context
To decide what to do with a review (notify, block, unblock, finalize), the bank needs a complete and up-to-date picture of it: status, blocking planned date, documents of every member, KYC data, KYT, non i-Hub blocks, customer profile, state of the unblocking. Today KYCO gathers this by hand from several screens.

The Review Context gathers these **facts** once. It does **not** decide anything: the eligibility and the automation conditions of each action live in the rules (tickets 8 to 11), each rule owning its own conditions.

### Why the Review Context is the entry point
- Every evaluation starts by collecting the facts, in one place, before any decision.
- All rules read the same facts, collected at the same moment.
- Rules contain only business conditions: no service call, no repository.

### Technical description
Input: `reviewId`.

1. **Load the review:** get the `Review` from the database and put it in the context.
   - Review not found, or status not in (`CCO`, `PREPARE_COM`, `BLOCKED`): no context, logged.
2. **Documents:** call ticket 1 with the `Review`. It returns `ReviewDocumentResult`.
3. **Other blocks:** call the block status service.
   - `nonIhubBlocksOk`: false as soon as one non i-Hub block is **amber**.
   - `kycDataAndKytOk`: false as soon as KYC data or KYT is KO, for the main party or the review.
4. **Unblocking action state:** read the status of the `UNBLOCKING_ACCOUNT` process action of the review (ticket 5).
5. **Party tiering, lazy:** the tiering comes from an API and is only needed by FR 0.A when a blocking could be automatic. The context does **not** call it: it exposes a lazy, memoized lookup. The API is called only the first time a rule asks for it, and at most once per evaluation.

```java
public record ReviewContext(
    Review review,                          // status, blocking planned date, pool, AML score, main DAO
    ReviewDocumentResult documents,         // ticket 1
    boolean nonIhubBlocksOk,
    boolean kycDataAndKytOk,
    ProcessActionStatus unblockingAction,   // ticket 5: INIT, TO_BE_PROCESSED, IN_PROGRESS, PROCESSED, ERROR
    PartyTieringLookup partyTiering         // lazy: calls the tiering API on first get(), then cached
) {}

public final class PartyTieringLookup {
    private final Supplier<Optional<Integer>> loader;   // wraps the tiering API call for the main party
    private Optional<Integer> value;
    public Optional<Integer> get() { if (value == null) value = loader.get(); return value; }
}
```

The `Review` is read only for the rules: they never modify it.

### What the context contains, and what it does not

| In the context (facts) | In the rules (conditions) |
|---|---|
| status, blocking planned date, pool, AML score, main DAO | eligibility of each action |
| `newValidation`, `allBlocksCompliant`, `pending`, `rejected` | automation conditions (Retail, Low, tiering, 831...) |
| `nonIhubBlocksOk`, `kycDataAndKytOk` | which action or which task |
| unblocking action state | |
| tiering, on demand | |

### Edge cases
- **Ticket 1 or the block status service fails:** no context, error logged with the review id, no rule runs.
- **Tiering API fails:** `partyTiering.get()` returns empty, logged. FR 0.A then creates a task, so KYCO decides.
- **Missing KYC data or KYT:** `kycDataAndKytOk = false`, logged.
- **No `UNBLOCKING_ACCOUNT` action on the review** (should not happen after ticket 5 backfill): logged as error.

### Acceptance criteria
- Given a `reviewId`, then the `Review` is loaded from the database and put in the context.
- Given an unknown review, or a review not in CCO, Prepare com or Blocked, then no context is built.
- Given ticket 1 returns its indicators, then they are in `context.documents()` unchanged.
- Given one non i-Hub block amber, then `nonIhubBlocksOk = false`.
- Given KYT KO on the main party, then `kycDataAndKytOk = false`.
- Given the unblocking action in `TO_BE_PROCESSED`, then `unblockingAction = TO_BE_PROCESSED`.
- Given the context is built, then the tiering API is not called.
- Given `partyTiering.get()` called twice, then the tiering API is called once.
- Given the tiering API in error, then `partyTiering.get()` returns empty.

### Open points for the team
- Name and contract of the block status service and of the tiering API.

---

## Ticket 3 - KYC review task: Spring Modulith event and Salesforce listener

**Type:** Technical story

### Business context
When a rule cannot act automatically, KYCO must receive a **Salesforce task** telling what to do on the review. There are four kinds of tasks, one per rule. KYCO must never receive the same task twice for the same review: an existing task is reopened and updated, not duplicated. A task must never be lost, even if Salesforce is unavailable.

### Technical description
Tasks are **Spring Modulith application events**. The event publication registry stores the publication in the same transaction as the rule result, and the listener runs after commit. Incomplete publications are resubmitted, so no task is lost.

```java
public record KycReviewTaskRequested(
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
}
```

1. **Publisher** `KycReviewTaskPublisher`: `applicationEventPublisher.publishEvent(new KycReviewTaskRequested(...))`, called inside the transaction of the evaluation.
2. **Listener** `KycReviewTaskListener` with `@ApplicationModuleListener`:
   - reads subject, description, queue and priority from `TaskType`,
   - looks for an existing Salesforce task with the key `reviewId + taskType`,
   - absent: create. Open: update. Closed: reopen and update.
3. **Resubmission** of incomplete publications (Salesforce down): use the Spring Modulith mechanism already configured in the project (resubmit on restart or scheduled resubmission).

Descriptions, queues and priorities are placeholders, to be provided by the business. Changing a value requires a release.

### Acceptance criteria
- Given the evaluation transaction commits, then the event publication is stored and the listener is called after commit.
- Given the evaluation transaction rolls back, then no event is published and no task is created.
- Given no existing task for this review and type, then one task is created with the values of its `TaskType`.
- Given an open task of the same type, then it is updated, no duplicate.
- Given a closed task of the same type, then it is reopened and updated.
- Given Salesforce unavailable, then the publication stays incomplete and the task is created once it is resubmitted.

### Open questions for the business
- Uniqueness key `review + task type`: correct on the Salesforce side?
- Open task and nothing changed: update on every evaluation or only on change?
- Task closed by KYCO without doing the action: reopen it?
- Task open for a long time: reminder after X days?

---

## Ticket 4 - Rule framework and rule outcome executor

**Type:** Technical story

### Business context
Each of the four rules answers the same three questions: is the review **eligible**, can the action be done **automatically**, and **which action**. If it cannot be automatic, KYCO gets a task. Putting this pattern in one place guarantees that every rule behaves the same way and makes each rule small and easy to review with the business.

### Technical description

**1. Template of a rule**

```java
public abstract class ReviewRule {

    public final RuleOutcome evaluate(ReviewContext ctx) {
        if (!isEligible(ctx)) return new NotApplicable(type());
        if (canAutomate(ctx)) return new AutomaticAction(type(), automaticAction());
        return new TaskRequested(type(), taskType());
    }

    protected abstract RuleType type();
    protected abstract boolean isEligible(ReviewContext ctx);
    protected abstract boolean canAutomate(ReviewContext ctx);
    protected abstract ReviewAction automaticAction();
    protected abstract TaskType taskType();
}
```

**2. What a rule returns**

```java
public sealed interface RuleOutcome {
    record NotApplicable(RuleType rule) implements RuleOutcome {}
    record AutomaticAction(RuleType rule, ReviewAction action) implements RuleOutcome {}
    record TaskRequested(RuleType rule, TaskType taskType) implements RuleOutcome {}
}

public sealed interface ReviewAction {
    record TransitStatus(ReviewStatus target) implements ReviewAction {}   // BLOCKED, REMEDIATED
    record TriggerUnblocking() implements ReviewAction {}                  // UNBLOCKING_ACCOUNT action
    record None() implements ReviewAction {}                               // FR 0.0
}
```

**3. Executor**

```java
@Component
class RuleOutcomeExecutor {

    void apply(Review review, RuleOutcome outcome) {
        switch (outcome) {
            case NotApplicable n   -> { }
            case AutomaticAction a -> execute(review, a.rule(), a.action());
            case TaskRequested t   -> taskPublisher.publish(review.getId(), t.taskType());
        }
    }

    private void execute(Review review, RuleType rule, ReviewAction action) {
        switch (action) {
            case TransitStatus t     -> reviewStatusService.transition(review.getId(), t.target(), Actor.system(rule));
            case TriggerUnblocking u -> processActionService.trigger(review.getId(), UNBLOCKING_ACCOUNT);
            case None n              -> { }
        }
    }
}
```

- `reviewStatusService.transition` is the **existing** service used when KYCO changes the status by hand (transition checks, history, audit). Actor = system with the rule name.
- `processActionService.trigger` moves the `UNBLOCKING_ACCOUNT` action of the review to execution (ticket 5 and 6).
- `taskPublisher` is the publisher of ticket 3.
- Everything runs in the transaction of the caller (orchestrator, ticket 12).

### Acceptance criteria
- Given a rule not eligible, then `evaluate` returns `NotApplicable` and `canAutomate` is not called.
- Given a rule eligible and automatic, then `AutomaticAction` with the rule's action.
- Given a rule eligible and not automatic, then `TaskRequested` with the rule's task type.
- Given `TransitStatus(REMEDIATED)`, then the existing transition service is called with actor system.
- Given `TriggerUnblocking`, then the `UNBLOCKING_ACCOUNT` action of the review is triggered.
- Given `TaskRequested(FR_0_C)`, then `KycReviewTaskRequested` is published.
- Given the transition fails, then nothing is published (same transaction).

---

## Ticket 5 - Process action `UNBLOCKING_ACCOUNT`: type, init, lifecycle and backfill

**Type:** Technical story

### Business context
Unblocking a customer means **removing the posting restriction** from the account. Like the blocking, it must go through a **process action** on the review: it is traceable, it is executed once, and it is never relaunched once processed. Today there is no such action for the unblocking. It must exist on every review, become ready as soon as the customer is blocked, and be added to the reviews already in progress.

### Technical description

**1. New process action type** `UNBLOCKING_ACCOUNT`, next to the existing blocking action.

**2. Lifecycle**

| Status | When |
|---|---|
| `INIT` | created with the review (same place as the other process actions) |
| `TO_BE_PROCESSED` | when the blocking action of the review is processed (account blocked) |
| `IN_PROGRESS` | triggered by FR 0.B (automatic) or by KYCO (manual, after task FR 0.B) |
| `PROCESSED` | posting restriction removed by the adapter (ticket 6) |
| `ERROR` | adapter call failed (ticket 6) |

Status names to align with the existing process action statuses.

**3. Init:** create the `UNBLOCKING_ACCOUNT` action in `INIT` for every new review, in the same place where the other process actions are initialized.

**4. Transition to `TO_BE_PROCESSED`:** when the blocking action passes to processed, move the unblocking action of the same review to `TO_BE_PROCESSED`. Same transaction as the blocking update.

**5. Backfill of the reviews in progress** (database migration):

| Review today | `UNBLOCKING_ACCOUNT` created in |
|---|---|
| CCO or Prepare com, not blocked | `INIT` |
| Blocked, blocking action processed | `TO_BE_PROCESSED` |
| Remediated or closed | not created |

The migration is idempotent: a review that already has the action is not touched.

**6. Manual trigger:** KYCO can trigger the action from the review after a task FR 0.B. Use the existing way process actions are triggered by users, if any. To confirm.

### Acceptance criteria
- Given a new review, then an `UNBLOCKING_ACCOUNT` action exists in `INIT`.
- Given the blocking action passes to processed, then the unblocking action of the same review passes to `TO_BE_PROCESSED`.
- Given the blocking action fails, then the unblocking action stays in `INIT`.
- Given the migration runs, then each review in progress gets the action with the status of the table above.
- Given the migration runs twice, then no duplicate action is created.
- Given the action in `PROCESSED`, then it can never be triggered again.

---

## Ticket 6 - Unblocking account execution: listener, outbox and adapter call

**Type:** Technical story
**Depends on:** posting restriction adapter (other ticket, in progress)

### Business context
Once the unblocking is decided (automatically by FR 0.B or by KYCO), the posting restriction must really be removed from the customer account in the core banking system. The call must be reliable: if the core banking system is down, the unblocking must be retried, never lost, and never done twice.

### Technical description
1. **Listener:** when the `UNBLOCKING_ACCOUNT` action passes to `IN_PROGRESS`, a listener saves an outbox entry (same transaction as the status change).
2. **Outbox handler:** reads the entry and calls the **posting restriction adapter** with "remove posting restriction" for the account(s) of the review.
3. **Result:**
   - success: action `PROCESSED`, entry completed,
   - failure: retry by the outbox; after the maximum retries, action `ERROR`, logged with review id and account id.
4. **Idempotency:** an action already `PROCESSED` is ignored by the handler.

```
FR 0.B or KYCO
  -> UNBLOCKING_ACCOUNT : IN_PROGRESS
       -> listener -> outbox entry (same transaction)
            -> outbox handler -> posting restriction adapter: remove restriction
                 -> success : PROCESSED   (FR 0.C can now finalize the review)
                 -> failure : retry, then ERROR
```

### Acceptance criteria
- Given the action passes to `IN_PROGRESS`, then one outbox entry is stored in the same transaction.
- Given the adapter succeeds, then the action is `PROCESSED`.
- Given the adapter fails, then the outbox retries; after the maximum, the action is `ERROR` and the error is logged.
- Given the same entry handled twice, then the adapter is called only once.
- Given a rollback of the status change, then no outbox entry exists.

### Open questions for the business
- Unblocking action in `ERROR`: retry later, or alert KYCO after N failures?

---

## Ticket 7 - Migrate account blocking to the posting restriction adapter

**Type:** Technical story
**Depends on:** posting restriction adapter (other ticket, in progress)

### Business context
Blocking and unblocking are two sides of the same operation on the customer account: put a posting restriction, remove it. Today the blocking uses its own integration. Using the same adapter for both guarantees that what one puts, the other can remove, and gives one place to maintain the core banking integration.

### Technical description
- Replace the current integration used by the blocking process action with the posting restriction adapter, operation "put posting restriction".
- Keep the existing behaviour of the blocking action: statuses, retries, error handling, audit.
- On success of the blocking, ticket 5 moves the unblocking action to `TO_BE_PROCESSED`.
- Remove the old integration once the migration is validated (or keep it behind a feature flag during the transition, to decide within the team).

### Acceptance criteria
- Given a blocking action triggered, then the adapter is called with "put posting restriction" for the account(s) of the review.
- Given the adapter succeeds, then the blocking action is processed, as today.
- Given the adapter fails, then the existing retry and error handling apply.
- Given a customer blocked through the adapter, then the unblocking through the same adapter removes the restriction (integration test).
- Non-regression: the existing blocking tests still pass.

---

## Ticket 8 - Rule FR 0.0: documents completed

**Type:** Technical story

### Business context
When the last i-Hub document of a review is validated, the i-Hub part is done. If KYCO still has **manual analysis** to do on non i-Hub blocks (at least one amber), KYCO must be told that the i-Hub documents are complete and that the review can be followed up.
- This rule **never changes the review**: there is no automatic action, it only creates a task for KYCO.
- It has **no condition on the blocking planned date** (decision D1): it can fire before or after the date, and in the same evaluation as FR 0.A.
- It also applies to a **Blocked** review: documents can be completed while the customer is blocked.
- If all non i-Hub blocks are already OK, there is nothing to tell KYCO: FR 0.B or FR 0.C take over.

### Rule
```
eligible   : status in (CCO, PREPARE_COM, BLOCKED)
             and allBlocksCompliant
             and newValidation
             and not nonIhubBlocksOk
automatic  : never                       -> canAutomate() always returns false
otherwise  : task FR 0.0                 -> "KYC REVIEW - i-HUB documents complete. Ready for follow-up"
```

### Technical description
- `DocumentsCompletedRule extends ReviewRule` (ticket 4).
  - `isEligible`: the four conditions above, all read from the context.
  - `canAutomate`: always `false`.
  - `automaticAction`: `ReviewAction.None` (never used).
  - `taskType`: `TaskType.FR_0_0`.
- `newValidation` must come from the **persisted** marker of ticket 1 (option A flag or option B timestamp), not from the in-memory result of the last FTKYP-646 call. Otherwise a failed evaluation loses the notification.
- The marker is reset by the orchestrator in phase 2 (ticket 12), whether FR 0.0 created a task or not. So the same validation never produces two tasks.
- The task is idempotent anyway (ticket 3): if a FR 0.0 task already exists for the review, it is updated, not duplicated.

### Acceptance criteria
- Given status CCO, documents compliant, a block just validated, one non i-Hub block amber, then `TaskRequested(FR_0_0)`.
- Given the same with status Prepare com or Blocked, then `TaskRequested(FR_0_0)`.
- Given the blocking planned date passed, then the rule still applies.
- Given all non i-Hub blocks green or grey, then `NotApplicable`.
- Given no block just validated, then `NotApplicable`.
- Given documents not all compliant, then `NotApplicable`.
- Given status Remediated, then `NotApplicable`.
- Given the evaluation failed after a block was validated, then the next evaluation still returns `TaskRequested(FR_0_0)` (persisted marker).
- The rule never returns `AutomaticAction`.

---

## Ticket 9 - Rule FR 0.A: blocking

**Type:** Technical story

### Business context
The customer had until the **blocking planned date** to provide what was asked. Once it is passed and the review is still incomplete (documents not compliant, or KYC data / KYT KO), the customer must be **blocked**.
- Low-risk customers (tiering 4 or 5, Retail, AML Low) are blocked automatically, unless a document was **rejected**: a rejection needs a human look.
- Other customers: KYCO receives a task.
- A **pending** document (provided by the customer, under back-office validation) pauses the blocking: the customer did the job.

### Rule
```
eligible   : status in (CCO, PREPARE_COM) and blockingPlannedDate < today and not pending
             and (not allBlocksCompliant or not kycDataAndKytOk)
automatic  : pool = Retail and amlScore = Low and not rejected
             and partyTiering in (4, 5)              -> TransitStatus(BLOCKED)
otherwise  : task FR 0.A
```

In `canAutomate`, check pool, AML score and `rejected` first, and ask `partyTiering.get()` last: the tiering API is then called only when a blocking could really be automatic.

The transition to `Blocked` triggers the existing blocking process action (migrated to the adapter by ticket 7). "today" comes from an injected `Clock`.

### Acceptance criteria
- Given eligible, Retail, Low, no rejected document, tiering 4 or 5, then `AutomaticAction(TransitStatus(BLOCKED))`.
- Given eligible, Retail, Low, no rejected document, tiering 2, then `TaskRequested(FR_0_A)`.
- Given eligible, not Retail or not Low, then `TaskRequested(FR_0_A)` and the tiering is not requested.
- Given eligible with a rejected document, then `TaskRequested(FR_0_A)` and the tiering is not requested.
- Given not eligible, then the tiering is not requested.
- Given the tiering unavailable, then `TaskRequested(FR_0_A)`.
- Given documents compliant but KYC data / KYT KO, then the rule applies.
- Given a pending document, then `NotApplicable`.
- Given the date not passed, or status Blocked, then `NotApplicable`.

### Open questions for the business
- Pending document: is there a back-office delay after which KYCO is alerted?
- Only non i-Hub blocks KO (documents and KYC OK): no blocking, intended?
- Tiering "4 and 5" means "4 or 5"?

---

## Ticket 10 - Rule FR 0.B: unblocking

**Type:** Technical story

### Business context
A blocked customer who provided everything must get access to the account again. FR 0.B **only unblocks the account**, through the `UNBLOCKING_ACCOUNT` process action. The review stays `Blocked`: finalizing it is the job of FR 0.C.
- Retail, AML Low (831 included): automatic unblocking.
- Other customers: task FR 0.B, KYCO unblocks by hand.
- No unblocking while a non i-Hub block is still amber.

### Rule
```
eligible   : status = BLOCKED and allBlocksCompliant and nonIhubBlocksOk
             and unblockingAction = TO_BE_PROCESSED
automatic  : pool = Retail and amlScore = Low   -> TriggerUnblocking   (status unchanged)
otherwise  : task FR 0.B
```

### Acceptance criteria
- Given eligible, Retail, Low (831 or not), then `AutomaticAction(TriggerUnblocking)`.
- Given eligible, not Retail or not Low, then `TaskRequested(FR_0_B)`.
- Given the unblocking action `INIT`, `IN_PROGRESS`, `PROCESSED` or `ERROR`, then `NotApplicable`.
- Given one non i-Hub block amber, then `NotApplicable`.
- The rule never returns a status transition.

### Open questions for the business
- Document invalid again after the unblocking, account already open: who blocks again?
- Should the unblocking also require KYC data / KYT OK? Without it, a review blocked only for KYC would be unblocked with KYC still KO.

---

## Ticket 11 - Rule FR 0.C: validation / finalization (STP)

**Type:** Technical story

### Business context
A review is ready to close when every document is compliant and every non i-Hub block is OK. Two paths lead there:
- the review was **never blocked**: CCO and blocking planned date passed,
- the review was **blocked and its account is unblocked**: unblocking action processed.

Low-risk retail customers outside Retail International (831) are finalized automatically (`Remediated`). For the others, KYCO receives task FR 0.C.

### Rule
```
eligible   : allBlocksCompliant and nonIhubBlocksOk
             and ( (status = CCO and blockingPlannedDate < today)
                or (status = BLOCKED and unblockingAction = PROCESSED) )
automatic  : pool = Retail and amlScore = Low and mainDao != 831   -> TransitStatus(REMEDIATED)
otherwise  : task FR 0.C
```

### Acceptance criteria
- Given CCO, date passed, all OK, auto profile, then `AutomaticAction(TransitStatus(REMEDIATED))`.
- Given the same with main DAO 831, then `TaskRequested(FR_0_C)`.
- Given Blocked, unblocking processed, auto profile, then `AutomaticAction(TransitStatus(REMEDIATED))`.
- Given Blocked, unblocking processed, not auto profile, then `TaskRequested(FR_0_C)`.
- Given Blocked, unblocking not processed (any other status), then `NotApplicable`.
- Given CCO, date not passed, then `NotApplicable`.

### Open questions for the business
- Why wait for the blocking planned date to finalize a review complete before?
- 831: account unblocked automatically, review Blocked until KYCO finalizes. Confirm?
- Should finalization also require KYC data / KYT OK?

---

## Ticket 12 - Rule orchestrator and end-to-end tests

**Type:** Technical story

### Business context
For a given review, the four rules must be applied in a fixed and predictable order, so that KYCO never receives contradictory tasks and the review never ends in an inconsistent state.

### Technical description

The evaluation runs in **two phases**, so that no database transaction stays open during the calls to other services (iHub, block status service, tiering API).

```java
public EvaluationReport evaluate(String reviewId) {
    // Phase 1 - collect and decide, no global transaction
    ReviewContext ctx = contextService.build(reviewId);              // ticket 2 (remote calls)
    List<RuleOutcome> outcomes = rules.stream()                      // @Order: FR 0.0, 0.A, 0.B, 0.C
        .map(rule -> rule.evaluate(ctx))                             // pure, tiering fetched lazily if needed
        .toList();

    // Phase 2 - apply, one short transaction
    return applier.apply(ctx.review().getId(), ctx.review().getVersion(), outcomes);
}

@Transactional
public EvaluationReport apply(String reviewId, long expectedVersion, List<RuleOutcome> outcomes) {
    Review review = repository.findById(reviewId).orElseThrow();
    if (review.getVersion() != expectedVersion) return EvaluationReport.skipped(reviewId);   // changed meanwhile
    outcomes.forEach(o -> executor.apply(review, o));                                       // ticket 4
    review.markDocumentsEvaluated();                                                        // newValidation reset (ticket 1 option A or B)
    return EvaluationReport.of(reviewId, outcomes);
}
```

- **Phase 1** has no global transaction: FTKYP-646 uses its own short local transactions, the other calls are read only.
- **Phase 2** only touches the local database and lasts milliseconds: status changes, process action triggers, task events and the reset of the `newValidation` marker are committed together, or not at all.
- **Optimistic locking (`@Version`)**: if the review changed between the two phases (KYCO action, other process), nothing is applied and the next evaluation decides again.
- Status changes are taken into account at the **next** evaluation. Example: a review blocked by FR 0.A is not unblocked in the same evaluation.
- The unblocking is asynchronous (ticket 6): FR 0.C finalizes a blocked review at the evaluation that follows the `PROCESSED` status.
- `EvaluationReport`: review id and outcome of each rule, logged for audit.

### Acceptance criteria
- Given documents just completed, a non i-Hub block amber and KYC KO after the date, then both FR 0.0 and FR 0.A outcomes are produced.
- Given a review blocked by FR 0.A, then FR 0.B does not apply in the same evaluation.
- Given a blocked low-risk review now complete, then evaluation N triggers the unblocking, and the evaluation after `PROCESSED` sets `Remediated`.
- Given a failure while applying an outcome, then nothing is stored for this review and the `newValidation` marker is kept.
- Given the review modified between phase 1 and phase 2, then nothing is applied and the report is marked skipped.
- Given phase 1, then no database transaction is open during the remote calls.
- One integration test per business scenario agreed with the business.

---

## Ticket 13 - Review selection and scheduling (later)

**Type:** Technical story, to detail later

### Business context
Every open review must be re-evaluated regularly, so that a validated document, a passed date or a KYCO action is taken into account without manual intervention.

### Outline
- Select reviews in `Client Communication Ongoing`, `Prepare Client Communication` or `Blocked`.
- Call the orchestrator for each one. One failing review does not stop the others.
- Frequency, batching, locking (no parallel evaluation of the same review) and monitoring: to define.
