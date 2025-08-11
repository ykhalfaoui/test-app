@startuml
class Review {
  +UUID id
  +ReviewScope scope  // PIVOT | FAMILY | MIXED
  +ReviewStatus status
  +LocalDateTime createdAt
  +LocalDateTime closedAt
  +UUID pivotId      // Customer id
}

class ReviewSubject {
  +UUID id
  +UUID reviewId
  +SubjectRole role  // PIVOT | MEMBER | ATTORNEY
  +UUID customerId
}

abstract class BlockInstance {
  +UUID id
  +BlockStatus status
  +Validity(validFrom, validTo?)
  +UUID subjectId   // ReviewSubject
  +Fingerprint fp   // pour déduplication
  +UUID sourceReviewId  // si réutilisé
}

class StaticDataBlock {
  // champs spécifiques static data
}
class DocumentBlock {
  // champs spécifiques document
}
class KYTBlock {
  // champs spécifiques KYT
}
class NameScreeningBlock {
  +LocalDateTime screeningDate
  +LocalDateTime validUntil
}

BlockInstance <|-- StaticDataBlock
BlockInstance <|-- DocumentBlock
BlockInstance <|-- KYTBlock
BlockInstance <|-- NameScreeningBlock

class KycProfile {
  +UUID id
  +UUID customerId
  +LocalDateTime generatedAt
  +ProfileStatus status  // CURRENT | ARCHIVED
  +updateBlock(newBlockId: UUID)
}

class KycProfileItem {
  +UUID id
  +BlockType type
  +UUID blockId // dernier block actif de ce type
  +LocalDateTime linkedAt
}

KycProfile "1" o-- "1..*" KycProfileItem
KycProfileItem --> BlockInstance : references latest

Review "1" o-- "1..*" ReviewSubject
ReviewSubject "1" o-- "0..*" BlockInstance


note right of KycProfile
- Contient la dernière version APPROVED de chaque BlockType
- Lorsqu’un nouveau Block APPROVED est créé, le profil met à jour le lien vers ce Block et supprime le lien vers l’ancien
- Vue consolidée en temps réel pour chaque client
end note

note right of BlockInstance
- Fingerprint = hash(type, subject/customer, keyFields)
- Unique partial index sur statuts actifs
- NAME_SCREENING: validité 6 mois
end note
@enduml
