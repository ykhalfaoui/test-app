Voici un **blueprint** prêt à l’emploi (FR) avec **diagrammes PlantUML** pour ton intégration **Spring Modulith → Salesforce** en 3 phases (**Family → Blocks → Review**), incluant modules, événements, états, séquences et modèle de données.

---

# Blueprint d’intégration Salesforce (Spring Modulith)

## Objectifs

* Orchestration interne par **événements applicatifs** (AFTER_COMMIT).
* **Aucune IO externe** dans une transaction DB.
* **Outbox CRM** pour envoyer vers Salesforce en 3 étapes ordonnées et idempotentes.
* Reprise avec **retries**, **backoff** et **DLQ**.

## Modules (suggestion)

* `hit` (qualification de hit)
* `review` (agrégat + process manager “readiness”)
* `family` (construction famille)
* `blocks` (construction des blocs)
* `crm` (outbox + dispatcher Salesforce)
* `shared` (types d’événements, IDs, utilitaires)

## Événements (payloads minces)

* `HitQualified(hitId, status)`
* `ReviewCreated(reviewId, hitId)`
* `MembersBuilt(reviewId)`
* `BlocksBuilt(reviewId)`
* `ReviewReadyForCRM(reviewId)`

## Tables clés (Oracle — extrait)

* `review(review_id, status, created_at, updated_at, sf_id NULL)`
* `review_process(review_id PK, members_status, blocks_status, crm_status, last_transition_at)`
* `family_snapshot(review_id PK, principal_party_id, members_json, roles_json, computed_at)`
* `block(block_id PK, review_id, subject_party_id, block_type, version, status, created_at, updated_at, sf_id NULL)`
* `crm_outbox(id, review_id, task_type, payload, dedupe_key UNIQUE, status, attempts, available_at, last_error, created_at, sent_at)`

---

## Diagrammes PlantUML

### 1) Contexte & modules (composants)

```plantuml
@startuml
title Contexte & Modules (Spring Modulith) + Frontières externes

package "Monolithe (Spring Modulith)" {
  [hit] as HIT
  [review] as REVIEW
  [family] as FAMILY
  [blocks] as BLOCKS
  [crm] as CRM
  [shared] as SHARED

  HIT -down-> REVIEW : HitQualified
  REVIEW -down-> FAMILY : ReviewCreated
  FAMILY -down-> BLOCKS : MembersBuilt
  BLOCKS -left-> REVIEW : BlocksBuilt
  REVIEW -right-> CRM : ReviewReadyForCRM
}

rectangle "Systèmes externes" {
  [iHub-adapter] as IHUB
  [NODS] as NODS
  [T24] as T24
  [Salesforce] as SF
}

FAMILY .. IHUB : Relations famille (IO)
FAMILY .. NODS : Infos digitales (IO)
REVIEW .. T24 : (option) enr. référentiels (IO)
CRM .. SF : Upsert Family/Blocks/Review (IO)

note right of CRM
- Outbox CRM (Oracle)
- Scheduler d’envoi
- Retries/Backoff/DLQ
- Idempotence ExternalId
end note
@enduml
```

### 2) Flux bout-à-bout (événements internes + gate CRM)

```plantuml
@startuml
title E2E : Qualification → Review → Members → Blocks → Gate CRM → Envoi SF

actor Source as SRC
participant "hit" as HIT
participant "review\n(process manager)" as REVIEW
participant "family" as FAMILY
participant "blocks" as BLOCKS
participant "crm\n(outbox)" as CRM
database "Oracle DB" as DB

== Réception & qualification ==
SRC -> HIT : Commande/REST: receiveHit()
activate HIT
HIT -> DB : persist Hit + compute status
HIT --> REVIEW : Event AFTER_COMMIT: HitQualified(POSITIVE)
deactivate HIT

== Création Review ==
activate REVIEW
REVIEW -> DB : create Review + ReviewProcess{members=PENDING,blocks=PENDING,crm=PENDING}
REVIEW --> FAMILY : Event AFTER_COMMIT: ReviewCreated
deactivate REVIEW

== Construction Members (IO hors TX) ==
activate FAMILY
FAMILY -> IHUB : getRelations(principal)  <<IO>>
FAMILY -> NODS : getDigitalInfos(members)  <<IO>>
FAMILY -> DB : persist FamilySnapshot, roles, impacts
FAMILY --> REVIEW : Event: MembersBuilt(reviewId)
deactivate FAMILY

== Construction Blocks ==
activate BLOCKS
BLOCKS -> DB : create Blocks + Inputs + status initial + attach to Review
BLOCKS -> DB : update ReviewProcess.blocks=DONE
BLOCKS --> REVIEW : Event: BlocksBuilt(reviewId)
deactivate BLOCKS

== Gate CRM Readiness ==
activate REVIEW
REVIEW -> DB : check members=DONE && blocks=DONE
REVIEW -> DB : update crm_status=PENDING->QUEUED
REVIEW --> CRM : Event: ReviewReadyForCRM
deactivate REVIEW

== Outbox CRM (planification) ==
activate CRM
CRM -> DB : insert crm_outbox UPSERT_FAMILY
CRM -> DB : insert crm_outbox UPSERT_BLOCKS
CRM -> DB : insert crm_outbox UPSERT_REVIEW
deactivate CRM
@enduml
```

### 3) Séquence d’envoi Salesforce (ordonnancement outbox)

```plantuml
@startuml
title Ordre strict d'envoi vers Salesforce (Family → Blocks → Review)

participant "crm\n(scheduler)" as SCHED
database "crm_outbox" as OX
participant "Salesforce API" as SF

== Family ==
SCHED -> OX : SELECT READY WHERE type=UPSERT_FAMILY ORDER BY created_at LIMIT N
loop pour chaque task
  SCHED -> SF : Bulk UPSERT Parties/Family/FamilyMember
  alt succès
    SCHED -> OX : mark SENT
  else erreur retryable
    SCHED -> OX : attempts++, available_at=now+backoff
  else erreur non-retryable
    SCHED -> OX : mark FAILED + DLQ
  end
end

== Blocks (seulement si plus de FAMILY en READY) ==
SCHED -> OX : SELECT READY WHERE type=UPSERT_BLOCKS ...
... (mêmes règles) ...

== Review (seulement si plus de BLOCKS en READY) ==
SCHED -> OX : SELECT READY WHERE type=UPSERT_REVIEW ...
... (mêmes règles) ...
@enduml
```

### 4) États du `ReviewProcess` (gate “CRM readiness”)

```plantuml
@startuml
title États ReviewProcess (gate CRM)

[*] --> INIT
state INIT {
  [*] --> PENDING
}

PENDING --> MEMBERS_DONE : on MembersBuilt
PENDING --> BLOCKS_DONE : on BlocksBuilt

state READY_CHECK #DDFFDD {
  MEMBERS_DONE --> BOTH_DONE : if blocks=DONE
  BLOCKS_DONE  --> BOTH_DONE : if members=DONE
}

BOTH_DONE --> QUEUED_CRM : emit ReviewReadyForCRM + plan CRM tasks
QUEUED_CRM --> CRM_DONE : on CRM success
QUEUED_CRM --> CRM_FAILED : on DLQ / non-retryable

CRM_FAILED --> QUEUED_CRM : manual retry
@enduml
```

### 5) Modèle de données (classes essentielles)

```plantuml
@startuml
title Modèle de données (simplifié)

class Review {
  +reviewId: String
  +status: String
  +sfId: String?
  +createdAt: Timestamp
  +updatedAt: Timestamp
}

class ReviewProcess {
  +reviewId: String
  +membersStatus: String  <<PENDING|DONE|FAILED>>
  +blocksStatus: String   <<PENDING|DONE|FAILED>>
  +crmStatus: String      <<PENDING|QUEUED|DONE|FAILED>>
  +lastTransitionAt: Timestamp
}

class FamilySnapshot {
  +reviewId: String
  +principalPartyId: String
  +membersJson: CLOB
  +rolesJson: CLOB
  +computedAt: Timestamp
}

class Block {
  +blockId: String
  +reviewId: String
  +subjectPartyId: String
  +blockType: String
  +version: int
  +status: String
  +sfId: String?
  +createdAt: Timestamp
}

class CrmOutbox {
  +id: number
  +reviewId: String
  +taskType: String <<UPSERT_FAMILY|UPSERT_BLOCKS|UPSERT_REVIEW>>
  +payload: CLOB
  +dedupeKey: String
  +status: String <<READY|SENT|FAILED>>
  +attempts: int
  +availableAt: Timestamp
  +lastError: CLOB
  +createdAt: Timestamp
  +sentAt: Timestamp?
}

Review "1" -- "1" ReviewProcess
Review "1" -- "1" FamilySnapshot
Review "1" -- "0..*" Block
Review "1" -- "0..*" CrmOutbox
@enduml
```

### 6) Séquence détaillée “UPSERT_FAMILY” (avec idempotence)

```plantuml
@startuml
title Détail UPSERT_FAMILY (idempotence ExternalId)

participant "crm\n(dispatcher)" as CRM
database "crm_outbox" as OX
participant "Mapper" as MAP
participant "Salesforce Bulk API" as SF

CRM -> OX : fetch READY(type=UPSERT_FAMILY)
CRM -> MAP : toBulkRows(payload)
MAP --> CRM : rows[Party, Family, FamilyMember]

CRM -> SF : Bulk UP SERT Party__c (ExternalId=party-*)
SF --> CRM : result (per-row)
CRM -> SF : Bulk UPSERT Family__c (ExternalId=family-{reviewId})
SF --> CRM : result
CRM -> SF : Bulk UPSERT FamilyMember__c (ExternalId=fam-{reviewId}-mem-{partyId})
SF --> CRM : result

alt all success
  CRM -> OX : mark SENT
else partial failures
  CRM -> OX : attempts++, available_at=now+backoff, store last_error (subset)
end
@enduml
```

---

## Bonnes pratiques (résumé)

* **AFTER_COMMIT** pour tous les listeners Modulith.
* Aucune dépendance externe dans une **TX DB** (IO après commit).
* **Outbox CRM** pour toute écriture Salesforce (ordonnée, idempotente).
* **ExternalId** déterministes : `party-{partyId}`, `family-{reviewId}`, `block-{blockId}`, `review-{reviewId}`.
* **Retries** exponentiels + **DLQ** pour erreurs non-retryables.
* **Observabilité** : `trace_id`, métriques backlog outbox, temps E2E Hit→CRM.

---

Si tu veux, je peux te générer ensuite les **squelettes Java** (entités JPA, listeners, services outbox, scheduler) en suivant exactement ces diagrammes.
