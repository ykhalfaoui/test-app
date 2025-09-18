FSM = **Finite State Machine** — en français **machine à états finis**.

### En deux mots

C’est un **modèle** qui décrit un objet (ex : ton **bloc Static data**) comme étant **dans un seul état à la fois**, et ne pouvant **changer d’état** que via des **transitions** autorisées, déclenchées par des **événements** (iHub 86/87/127/128, action agent SF…), parfois protégées par des **gardes** (conditions) et accompagnées d’**actions** (audit, publication d’évènement, MAJ DB).

### Pourquoi on l’utilise ici

* Empêcher les **transitions illégales** (ex. passer de `GREY_NOT_REVIEWED` directement à `RED_COMPLIANCE_FINAL_ASSESSMENT` sans passer par l’agent).
* Rendre l’implémentation **prévisible** et **auditable** (qui a fait quoi, quand, depuis quel événement).
* Centraliser la **règle métier** du cycle de vie du bloc (GO est le master).

### Vocabulaire rapide

* **État (state)** : `BLUE_UNDER_IHUB`, `GREY_NOT_REVIEWED`, `ORANGE_MANUAL_ANALYSIS`,
  `RED_PENDING_COMPLIANCE`, `RED_COMPLIANCE_FINAL_ASSESSMENT`,
  `GREEN_VALIDATED`, `GREEN_NOT_VALIDATED`.
* **Événement (event)** : `ihub_86`, `ihub_127`, `ihub_128`, `ihub_87(validated|not_validated|ambiguous)`, `sf_set_orange`, `sf_set_red_pending`, `sf_set_red_final`, `sf_set_green_validated`, `sf_set_green_not_validated`.
* **Garde (guard)** : condition, ex. “seulement si bloc appartenant à la review courante”, “si notifié par iHub”.
* **Action** : ce qu’on fait pendant la transition : mettre à jour la DB, tracer l’audit, publier `staticdata.status.updated`.

### Mini diagramme (idée)

```plantuml
@startuml
[*] --> GREY
GREY --> BLUE : review.started & isDigital
GREY --> ORANGE : sf_set_orange
BLUE --> GREEN_VALIDATED : ihub_86 or ihub_127
BLUE --> GREEN_NOT_VALIDATED : ihub_128
BLUE --> ORANGE : ihub_87(ambiguous) / sf_set_orange
ORANGE --> RED_PENDING_COMPLIANCE : sf_set_red_pending
RED_PENDING_COMPLIANCE --> RED_COMPLIANCE_FINAL_ASSESSMENT : sf_set_red_final
ORANGE --> GREEN_VALIDATED : sf_set_green_validated
ORANGE --> GREEN_NOT_VALIDATED : sf_set_green_not_validated
GREEN_VALIDATED --> GREEN_VALIDATED : idempotent no-op
GREEN_NOT_VALIDATED --> GREEN_NOT_VALIDATED : idempotent no-op
@enduml
```

### Comment l’implémenter (Spring, simple et robuste)

1. **Enum d’états** + **table de transitions** (Map\<State, Set<State>>) → validation centralisée.
2. **Service de transition** qui :

   * charge le bloc courant,
   * vérifie l’**événement** et les **gardes**,
   * applique la transition (MAJ `BLOCK_VERSION.state`, `decidedBy/At`, `notes`),
   * **audit** + **event interne**.
3. **Idempotence** : si l’état cible = état courant → **no-op** traçable.

> Optionnel : frameworks type **Spring Statemachine**. Pratique pour des FSM complexes, mais pour un FSM court comme ici, une implémentation “maison” (enum + validation) est souvent plus lisible et facile à tester.

Si tu veux, je peux te donner un **squelette de code** (enum, transition validator, service) basé sur tes états/événements.
