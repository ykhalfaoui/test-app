# test-app

# 360° Customer View - Initial Idea (KYC Remediation)

## Objective

Design a solution that provides a **360° view of a customer** within a **KYC remediation** context. The goal is to unify various internal and external data sources to:

* Holistically visualize a customer’s profile
* Accelerate and improve the reliability of KYC remediation processes
* Strengthen regulatory compliance (AML, FATF, GDPR, etc.)
* Support decision-making (risk-based approach)

## Initial Idea

### General Description

Automating customer remediation to obtain a 360° view of their KYC status.

Each customer (pivot) is associated with several distinct KYC blocks:

* **KYT (Know Your Transaction)**
* **Static Data**
* **Documents** (iHub or non-iHub)
* **Name Screening**
* **KYC (core identity review)**
* **Pivot Family**

---

## Block Behavior Summary (Orchestrated Flows)

### KYT Block - Sequence Summary

```plantuml
@startuml
actor Agent
participant "Review Orchestrator" as RO
participant "KYT Block" as KYT
participant "KYC Block" as KYC
database "DMS" as DMS
participant "Salesforce" as SF

RO -> KYT : Emit review_init_event
KYT -> KYT : Create block instance / Attach to pivot
KYT -> KYC : Get Shapley data from KYT automation
KYT -> KYT : Validate KYT data
alt Data valid
    KYT -> KYT : Set status = VALID
else Data invalid
    KYT -> KYT : Set status = NEED_KYC_REVIEW
    KYT -> KYT : Request transaction history
    KYT -> DMS : Archive transaction extract (after 1 day)
    Agent -> KYT : Optional: request data refresh
    Agent -> KYT : Start manual review
    alt Need client info
        KYT -> KYT : Set status = WAITING_INFO_CLIENT
    else Need compliance help
        KYT -> SF : Create compliance case
    end
    Agent -> KYT : Final analysis + commentary
    KYT -> KYT : Save result + update status
end
RO -> KYT : Emit 4-eyes check event
alt Agent decision = escalated or valid
    KYT -> DMS : Generate & archive final report
end
@enduml
```

### KYC Block - Sequence Summary

```plantuml
@startuml
actor Agent
participant "Review Orchestrator" as RO
participant "KYC Block" as KYC
participant "T24 System" as T24
participant "Salesforce" as SF

RO -> KYC : Emit review_init_event
KYC -> T24 : Retrieve KYC data in real-time
KYC -> KYC : Check if client is RETAIL
alt Retail client
    KYC -> KYC : Auto-set status = VALID
else Non-retail
    KYC -> KYC : Set status = NEED_AGENT_REVIEW
    Agent -> KYC : Start manual review
    alt Needs info from client
        KYC -> KYC : Set status = WAITING_INFO_CLIENT
    else Needs compliance help
        KYC -> SF : Create compliance case
    end
    Agent -> T24 : Update KYC data if needed
    Agent -> KYC : Validate / comment / escalate to compliance
end
@enduml
```

### Static Data Block - Sequence Summary

```plantuml
@startuml
actor Agent
participant "Review Orchestrator" as RO
participant "Static Data Block" as SD
participant "iHub" as iHub

RO -> SD : Emit review_init_event
SD -> SD : Check if block already exists for pivot & co-holder
alt Client = Non-digital
    SD -> SD : Set status = GRAY (manual only)
else Client = Digital
    SD -> SD : Set status = BLUE (waiting review on iHub)
end
Agent -> SD : Start review manually
alt Agent trusts iHub notifications
    iHub -> SD : Confirm client has reviewed static data
    SD -> SD : Archive confirmation & set status = VALID
else Manual override
    Agent -> SD : Set status = VALID with comment
end
@enduml
```

### iHub Documents / Non-iHub Documents Block - Concepts

* Support manual review by agents
* Final decision only after 4-eyes or compliance review
* iHub notifications may inform the status
* Archive all versions of documents in DMS
* Escalation logic similar to Static or KYC blocks

---

## Next Steps

1. Integrate class diagrams for each block.
2. Define architecture levels (2 & 3).
3. Identify tech stack and APIs per flow.

---

*(Document editable through future iterations)*
