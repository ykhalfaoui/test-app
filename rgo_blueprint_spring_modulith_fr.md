# Blueprint d'initialisation Spring Modulith (RGO – Remediation Global Orchestrator) – v1

> **Objectif** : guide prêt‑à‑l’emploi pour démarrer un monolithe modulaire Spring (Spring Modulith) pour **RGO**. Inclut : découpage des modules (section I), conventions, **Maven uniquement**, tests de vérification, documentation générée (diagrammes), intégration CI.

---

## A) Comprendre Spring Modulith — en termes simples (avec **jargon simplifié**)

**Problème courant :** monolithe « spaghetti » où tout dépend de tout.

**Solution Modulith :** on garde **un seul déploiement**, mais on le **structure en modules** avec **frontières vérifiées** (tests) et **diagrammes générés**. Simplicité du monolithe + discipline d’architecture (contrats, événements) — sans la complexité réseau des micro‑services.

### Bénéfices clés
- **Frontières vérifiées** : un test échoue si un module touche l’interne d’un autre.
- **Surface publique contrôlée** via `@NamedInterface`.
- **Doc vivante** : diagrammes générés depuis le code.
- **Événements fiables** : publication **après commit**.
- **Tests par module** : ciblés et rapides.

### Idées‑clés (sans jargon)
- **Module** = **package racine** (ex. `com.crok4it.rgo.staticdata`) avec sous‑packages `api/app/domain/infra/web/config`.
- **Interface publique** = ce qu’un autre module peut utiliser (`@NamedInterface`).
- **Événement de domaine** = « un **fait** s’est produit ».
- **After‑commit** = publication **après** validation DB.

---

## 1) Prérequis & versions
- **Java** : **17 (LTS)**
- **Spring Boot** : **3.4.x**
- **Build** : **Maven uniquement**
- **DB** : Postgres + Flyway
- **Outils** : Docker, Testcontainers, PlantUML (optionnel), GitHub Actions/CI

---

## 2) Démarrage rapide (Maven)

### 2.1 Archetype minimal (BOM + starters Modulith)
```xml
<properties>
  <java.version>17</java.version>
  <spring-boot.version>3.4.9</spring-boot.version>
  <spring-modulith.version>1.3.2</spring-modulith.version>
</properties>

<dependencyManagement>
  <dependencies>
    <!-- Spring Boot BOM -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <!-- Spring Modulith BOM -->
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-bom</artifactId>
      <version>${spring-modulith.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- Spring Modulith -->
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-core</artifactId>
  </dependency>
  <!-- JPA (si vous l’utilisez) : active le registry de publications -->
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-jpa</artifactId>
  </dependency>
  <!-- Tests Modulith (verify, docs) -->
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-test</artifactId>
    <scope>test</scope>
  </dependency>

  <!-- Boot + infra (exemples) -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <release>17</release>
      </configuration>
    </plugin>
  </plugins>
</build>
```

---

### 2.b Dépendances Maven Spring Modulith — **quoi ajouter et pourquoi**
| Artifact | À quoi ça sert | Quand l’ajouter | Notes |
|---|---|---|---|
| `spring-modulith-bom` | Centraliser les versions Modulith | **Toujours** | `${spring-modulith.version}` (ex. 1.3.2) |
| `spring-modulith-starter-core` | Découverte de modules, `@ApplicationModule`, `@NamedInterface`, `verify()` | **Toujours** | Base des frontières |
| `spring-modulith-starter-jpa` | Registry persistant des publications d’événements | Si **JPA** / registry | Re‑publication au redémarrage |
| `spring-modulith-starter-jdbc` | Registry sans JPA | Si pas JPA | Alternative légère |
| `spring-modulith-starter-mongodb` | Registry Mongo | Si Mongo | Choisir un seul mode |
| `spring-modulith-starter-test` | `@ApplicationModuleTest`, `Documenter`, verify | **Test** | Génère docs |

**Propriétés utiles (événements)**
```properties
spring.modulith.events.republish-outstanding-events-on-restart=true
spring.modulith.events.completion-mode=UPDATE   # ou DELETE, ARCHIVE
```

---

## 3) Convention d’un module (ex. `staticdata`)
```
com.crok4it.rgo.staticdata
 ├─ api/     # DTOs, ports (interfaces) @NamedInterface
 ├─ app/     # services applicatifs (cas d’usage, transactions)
 ├─ domain/  # modèle métier, agrégats, invariants
 ├─ infra/   # adaptateurs (JPA, REST externes)
 ├─ web/     # controllers REST minces
 └─ config/  # config locale au module
```

---

## 4) Annotations et frontières
- Annoter la racine avec `@ApplicationModule` (via `package-info.java`).
- Définir la **surface publique** avec `@NamedInterface`.
```java
@org.springframework.modulith.NamedInterface
package com.crok4it.rgo.staticdata.api;
```
- **Règle** : dépendre d’un autre module **uniquement** via son `api/`.

---

## 5) Événements after‑commit (hors `hit`)
```java
@org.springframework.stereotype.Service
class StaticDataUseCase {
  private final org.springframework.modulith.events.Events events; private final StaticDataRepo repo;
  @org.springframework.transaction.annotation.Transactional
  public StaticBlockId upsert(UpsertStaticBlockCmd cmd) {
    var block = repo.save(map(cmd));
    events.publish(new StaticBlockUpdated(block.getId(), cmd.requestId()));
    return block.getId();
  }
}

@org.springframework.stereotype.Component
class RiskRecalculationListener {
  @org.springframework.modulith.ApplicationModuleListener
  void on(StaticBlockUpdated evt) { /* recalcul risque asynchrone */ }
}
```
**Quand ?** ✅ pour des réactions non‑critiques (recalcul risque, index recherche). ❌ pas dans `hit` (latence maîtrisée, exécution simple).

---

## 6) Vérification architecturale (tests)
```java
@org.springframework.boot.test.context.SpringBootTest
class ModularityVerificationTest {
  @org.junit.jupiter.api.Test
  void modules_should_be_free_of_violations() {
    var modules = org.springframework.modulith.core.ApplicationModules.of(RgoApplication.class);
    modules.verify();
  }
}
```

---

## 7) Tests d’intégration ciblés par module
```java
@org.springframework.modulith.test.ApplicationModuleTest(verifyAutomatically = true)
class StaticDataModuleIT {
  @org.springframework.beans.factory.annotation.Autowired StaticDataUseCase useCase;
  @org.junit.jupiter.api.Test void creates_or_updates_static_block() { /* ... */ }
}
```

---

## 8) Documentation automatique (diagrammes)
```java
class DocumentationTests {
  var modules = org.springframework.modulith.core.ApplicationModules.of(RgoApplication.class);
  @org.junit.jupiter.api.Test
  void write_docs() {
    new org.springframework.modulith.docs.Documenter(modules)
      .writeModulesAsPlantUml()
      .writeIndividualModulesAsPlantUml()
      .writeModuleCanvases();
  }
}
```
**Sortie** : `target/spring-modulith-docs/` (Maven). Publier en artefacts CI.

---

## 9) Observabilité par module
- **Actuator** (health, metrics, info)
- **Micrometer** : tags par module
- **Logs corrélés** : `requestId`, nom de module (MDC)
- **Dashboards** : erreurs/module, latence `hit`, backlog listeners

---

## 12) Checklist d’initialisation
- [ ] Créer projet **Boot 3.4.x**, **Java 17**
- [ ] Ajouter starters Modulith (core, jpa, test)
- [ ] Définir **root package** et **modules** (voir I)
- [ ] Ajouter `@NamedInterface` pour chaque API
- [ ] Écrire **ModularityVerificationTest**
- [ ] Écrire **DocumentationTests**
- [ ] Implémenter l’API **`hit`** (idempotent + audit)
- [ ] Configurer Flyway & Testcontainers
- [ ] Actuator + métriques + logs corrélés
- [ ] CI : publier les diagrammes

---

## 13) Conventions de code — détaillées avec exemples
- **Nommage & packages** : `api/app/domain/infra/web/config` ; ports `*Port`, DTO `*Dto`/`*Cmd`, services d’app `*Service`.
- **DTO immuables** (records) + validation (`@Valid` côté web, règles métier en `domain`).
- **Controllers** minces → appellent des **ports**.
- **Services d’app** : transactions & orchestration ; publication d’événements.
- **Domaine** pur : invariants, aucune dépendance technique.
- **Infra** : JPA ≠ agrégats (mapper en infra).
- **Idempotence** : `requestId` + contrainte unique ; listeners idempotents.
- **Audit & logs** : évènement d’audit par mutation ; corrélation `requestId`.

---

## H) (réservé) Outbox & Retry — à traiter séparément

---

## I) Définition des modules RGO — découpage final

### I.1 Vue d’ensemble
| Module | Rôle & ownership | Tables | API publique | Événements | Dépendances |
|---|---|---|---|---|---|
| **customer** | Référentiel **Customer** & **Relations** | `customer`, `customer_relation` | `CustomerPort`, `/customers/*` | `CustomerChanged`, `CustomerRelationChanged` | `shared`, `audit` |
| **remediation-hit** | Reçoit un **hit**, (re)lance une **review** (sync, idempotent) | `hit_request` | `HitPort`, `POST /api/hit` | `HitProcessed` (opt.) | ports → `remediation-review`, `blocks`, `customer`; `shared`, `audit` |
| **remediation-review** | Possède **Review** + **review_member** (association/snapshot) | `review`, `review_member`, `review_target` | `ReviewsPort`, `/reviews/*` | `ReviewInitialized`, `ReviewProgressed`, `ReviewClosed` | `shared`, `audit`; ports → `customer`, `blocks`, `documentary` |
| **blocks** | Module unique pour types (KYC/KYT/Static/Doc…) | `block`, `block_type`, `block_state`, tables par type | `BlocksPort`, `/blocks/*` | `BlockCreated`, `BlockStateChanged` | `shared`, `audit` |
| **integration-salesforce** | Connecteur Salesforce | — | `SalesforcePort`, callbacks | — | `shared`, `projections` |
| **audit** | Journal immuable + export ELK | `audit_event` | `AppendAuditEvent`, `AuditQuery` | `AuditAppended` | `shared` |
| **reliability** | Erreurs & retry centralisés | `event_retry_meta` (+ registry) | `RetryPolicy`, jobs | — | `shared` |
| **bdwh** | Gateway Data Platform | — | `BdwhPort`, `/bdwh/*` | — | `shared` |
| **documentary** | Génération & suivi documents (DMS) | `generated_document`, `dms_link` | `DocumentaryPort`, `/documents/*` | `DocumentGenerated`, `DocumentUploaded` | `shared`, `audit` |
| **projections** | Read‑models (UI/reporting) | `read_model_*` | `ProjectionQuery` | — | événements de tous |

### I.2 Découpage interne (règles)
```
com.crok4it.rgo.<module>
├─ api/      # Ports + DTO publics (@NamedInterface)
├─ app/      # Services d'application (use cases, transactions, events after-commit)
├─ domain/   # Agrégats, VO, policies, invariants
├─ infra/    # JPA, clients externes, mapping
├─ web/      # Controllers REST minces
└─ config/   # Config locale
```

### I.3 Exemples d’API
```java
public interface CustomerPort { java.util.UUID register(RegisterCustomerCmd cmd); void linkRelation(java.util.UUID a, java.util.UUID b, RelationType type, String requestId); CustomerDto get(java.util.UUID id); }
public interface ReviewsPort { java.util.UUID startReview(StartReviewCmd cmd); void addReviewMember(java.util.UUID reviewId, java.util.UUID customerId, String role, String requestId); }
public interface BlocksPort { java.util.List<java.util.UUID> ensureBlocksForCustomer(java.util.UUID customerId, java.util.Set<BlockKind> kinds, String requestId); }
```

