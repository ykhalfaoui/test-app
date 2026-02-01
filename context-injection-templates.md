# 🔗 Context Injection Templates
**Passer le contexte entre conversations Copilot**

---

## Principe

```
Conversation 1 → Output → Résumé compact → Conversation 2 → Output → Résumé compact → ...
```

Chaque template fait **< 300 mots** pour éviter les timeouts Copilot.

---

## 📥 Template 0 : System Context (toutes conversations)

```text
You are a Senior Solution Architect and Tech Lead.
Rules: minimal output, bullets/tables only, no prose, English artifacts.
If info missing → list under "Open Questions" and stop.
```

---

## 📥 Template 1→2 : Blueprint → Backlog

À coller au début de la conversation Backlog :

```text
=== CONTEXT INJECTION ===
Feature: [nom]
Business Problem: [1 phrase]
Business Capability: [1 phrase]

Scope IN:
- [item 1]
- [item 2]
- [item 3]

Scope OUT:
- [item 1]
- [item 2]

Domains: [Domain1], [Domain2]
Aggregates: [Agg1 (root: Entity1)], [Agg2 (root: Entity2)]

Key Flows:
- Flow1: [trigger] → [outcome]
- Flow2: [trigger] → [outcome]

Key Business Rules:
- Rule1: [description]
- Rule2: [description]

Open Questions: [none / list]
=== END CONTEXT ===

Task: Generate Technical Stories for this feature.
```

---

## 📥 Template 2→3 : Backlog → Integration Tests

À coller au début de la conversation Integration Tests :

```text
=== CONTEXT INJECTION ===
Feature: [nom]
Domains: [Domain1], [Domain2]

Stories:
| ID | Title | Key AC |
|----|-------|--------|
| FEAT-001 | [title] | [main acceptance criteria] |
| FEAT-002 | [title] | [main acceptance criteria] |
| FEAT-003 | [title] | [main acceptance criteria] |

API Endpoints:
- POST /api/[resource] → [action]
- GET /api/[resource]/{id} → [action]

Events:
- [EventName] → triggered when [condition]

Error Cases:
- [Error1]: [condition] → [expected response]
- [Error2]: [condition] → [expected response]
=== END CONTEXT ===

Task: Generate Acceptance Contracts and Citrus test skeletons.
```

---

## 📥 Template 3→4 : Integration Tests → Architecture Review

À coller au début de la conversation Review :

```text
=== CONTEXT INJECTION ===
Feature: [nom]
Status: Ready for architecture review

Blueprint Summary:
- Problem: [1 phrase]
- Capability: [1 phrase]
- Domains: [list]
- Aggregates: [list]

Stories: [X] stories defined
- FEAT-001: [status]
- FEAT-002: [status]

Test Coverage:
- [X] acceptance criteria defined
- [X] happy paths identified
- [X] error scenarios identified

Key Decisions Made:
- [Decision 1]: [choice]
- [Decision 2]: [choice]

Risks Identified:
- [Risk 1]: [severity]
- [Risk 2]: [severity]
=== END CONTEXT ===

Task: Perform Architecture Review (Go/No-Go).
```

---

## 📥 Template 5 : Code Review (par ticket)

À coller pour chaque code review :

```text
=== CONTEXT INJECTION ===
Story: [FEAT-XXX]
Title: [story title]

Acceptance Criteria:
- [ ] AC1: [criteria]
- [ ] AC2: [criteria]
- [ ] AC3: [criteria]

Technical Scope:
- Module: [module name]
- Files changed: [list or count]
- Type: [new feature / bug fix / refactoring]

Dependencies:
- Upstream: [none / list]
- Downstream: [none / list]
=== END CONTEXT ===

Task: Review this code against the acceptance criteria.

Code diff:
[coller le diff ici ou référencer le fichier]
```

---

## 📋 Checklist : Quoi extraire de chaque étape

### Après Blueprint (pour injection 1→2)

Extraire :
- [ ] Feature name
- [ ] Business problem (1 phrase)
- [ ] Business capability (1 phrase)
- [ ] Scope IN (max 5 items)
- [ ] Scope OUT (max 3 items)
- [ ] Domains impliqués
- [ ] Aggregates (nom + root entity)
- [ ] Key flows (trigger → outcome)
- [ ] Business rules (max 3)
- [ ] Open questions

### Après Backlog (pour injection 2→3)

Extraire :
- [ ] Story IDs + titles
- [ ] Main AC par story (1 ligne)
- [ ] API endpoints
- [ ] Events émis
- [ ] Error cases principaux

### Après Integration Tests (pour injection 3→4)

Extraire :
- [ ] Nombre de stories
- [ ] Nombre d'AC
- [ ] Couverture (happy path, errors, edge cases)
- [ ] Décisions prises
- [ ] Risques identifiés

---

## 🚀 Workflow rapide

```
1. BLUEPRINT
   └─→ Sauvegarder output dans FEAT-XXX-blueprint.md
   └─→ Remplir Template 1→2
   └─→ Nouvelle conversation

2. BACKLOG
   └─→ Coller Template 1→2 + System Context
   └─→ Sauvegarder output dans FEAT-XXX-stories.md
   └─→ Remplir Template 2→3
   └─→ Nouvelle conversation

3. INTEGRATION TESTS
   └─→ Coller Template 2→3 + System Context
   └─→ Sauvegarder output dans FEAT-XXX-tests.md
   └─→ Remplir Template 3→4
   └─→ Nouvelle conversation

4. ARCHITECTURE REVIEW
   └─→ Coller Template 3→4 + System Context
   └─→ Sauvegarder output dans FEAT-XXX-review.md
   └─→ GO? → Passer à l'implémentation

5. CODE REVIEW (par PR)
   └─→ Coller Template 5 + diff
   └─→ Review et itérer
```

---

## 📁 Convention de fichiers recommandée

```
/docs/architecture/
  └── features/
      └── FEAT-XXX-[feature-name]/
          ├── 01-blueprint.md
          ├── 02-stories.md
          ├── 03-tests.md
          ├── 04-review.md
          └── adr/
              └── ADR-001-[decision].md
```

---

## Version
- **Version** : 1.0
- **Compatible** : GitHub Copilot Chat, VS Code, IntelliJ
- **Last updated** : 2025-02-01

