
@startuml
title Class Diagram – Party • KycReviewBlock (SCD2) • Hits • Relations • ReviewProcess • Audit (strings)

'===== CORE =====
class Party {
  +id: String
  +status: String
  +type: String
  +subType: String
  +relationsKycReviewStatus: String
  +kycReviewStatus: String
  +createdAt: OffsetDateTime
  +updatedAt: OffsetDateTime
}

class KycReviewBlock {
  +id: String
  +blockId: String          ' logical block identity (per party+kind)
  +partyId: String
  +kind: String             ' e.g. STATIC_DATA, DOCUMENTS, KYC, KYT, SCREENING
  +versionNo: int
  +validFrom: OffsetDateTime
  +validTo: OffsetDateTime?
  +status: String           ' IN_REVIEW / APPROVED / REJECTED
  +validatedAt: OffsetDateTime?
  +lastValidationDate: OffsetDateTime?
  +changedBy: String
  +changeReason: String
  +dataJson: String
}

' Vue (ou vue matérialisée) des versions courantes
class <<View>> CurrentKycBlockV {
  +blockId: String
  +kycReviewBlockId: String
  +partyId: String
  +kind: String
  +versionNo: int
  +effectiveFrom: OffsetDateTime
  ..note..
  Derived from KycReviewBlock where validTo IS NULL.
  Peut être une vue matérialisée en Oracle (FAST ON COMMIT).
  ..end note..
}

'===== HITS & REVIEW PROCESS =====
class Hit {
  +id: String
  +partyId: String          ' lien explicite Party → Hit
  +type: String             ' e.g. SANCTION, PEP, KYT_RULE
  +severity: String         ' LOW/MED/HIGH
  +status: String           ' OPEN / LINKED / RESOLVED / DUPLICATE / DISMISSED
  +occurredAt: OffsetDateTime
  +payloadJson: String
  +hashKey: String?
  +masterHitId: String?     ' si doublon
}

class ReviewInstanceProcess {
  +id: String
  +hitId: String            ' 1 hit → 0..1 process
  +status: String           ' OPEN / IN_PROGRESS / CLOSED
  +startedAt: OffsetDateTime
  +closedAt: OffsetDateTime?
  +outcome: String?         ' APPROVED / REJECTED / DISMISSED
  +notes: String?
}

'===== FAMILY / RELATIONS =====
class PartyRelation {
  +id: String
  +pivotPartyId: String     ' le “customer” pivot
  +memberPartyId: String    ' le membre lié
  +relationType: String     ' CO_HOLDER / MANDATAIRE / LEGAL_REP / ...
  +active: Boolean
  +status: String           ' ACTIVE / INACTIVE
  +validFrom: OffsetDateTime
  +validTo: OffsetDateTime?
  +remediationStatus: String ' PENDING / COMPLETED / ...
  +lastEvaluatedAt: OffsetDateTime?
  +comment: String?
}

'===== AUDIT =====
class AuditTrail {
  +id: String
  +entityType: String       ' PARTY / KYC_REVIEW_BLOCK / HIT / RELATION / REVIEW_PROCESS
  +entityId: String
  +action: String           ' CREATE / UPDATE / CLOSE / APPROVE / REJECT ...
  +actor: String
  +occurredAt: OffsetDateTime
  +detailsJson: String?
}

'===== ASSOCIATIONS =====
Party "1" o-- "0..*" KycReviewBlock
CurrentKycBlockV ..> KycReviewBlock : <<derives>>

Party "1" o-- "0..*" Hit : "has hits"
Hit "1" o-- "0..1" ReviewInstanceProcess : "triggers"

' Hit peut impacter plusieurs blocs (et inversement)
Hit "1" o-- "0..*" KycReviewBlock : "impacts (optionnellement une version courante)"

' Famille (pivot -> membre)
Party "1" o-- "0..*" PartyRelation : as pivot
Party "1" o-- "0..*" PartyRelation : as member

' Audit (générique)
AuditTrail ..> Party
AuditTrail ..> KycReviewBlock
AuditTrail ..> Hit
AuditTrail ..> PartyRelation
AuditTrail ..> ReviewInstanceProcess
@enduml
