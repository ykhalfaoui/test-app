# Full English Blueprint — Spring Modulith Initialization (RGO)

> **Goal:** A ready‑to‑use guide to kick off a modular monolith with **Spring Modulith** for **RGO – Remediation Global Orchestrator**. Includes: module breakdown (section I), conventions, **Maven only**, architectural verification tests, generated docs (diagrams), CI integration.

---

## A) Spring Modulith in plain English
**Problem:** a spaghetti monolith where everything depends on everything.

**Modulith:** keep a **single deployable**, split into **explicit modules** with **verified boundaries** and **generated diagrams**. Monolith simplicity + microservice‑like discipline (contracts, events) without network complexity.

### Benefits
- **Verified boundaries** (`verify()` fails on violations)
- **Controlled public surface** via `@NamedInterface`
- **Living docs**: diagrams generated from code
- **Reliable events**: **after‑commit** publication
- **Per‑module tests**

### Core ideas
- **Module** = **root package** (e.g., `com.crok4it.rgo.staticdata`) with `api/app/domain/infra/web/config`
- **Public interface** = what others may use (`@NamedInterface`)
- **Domain event** = “a **fact** happened”
- **After‑commit** = publish **after** DB commit

---

## 1) Prereqs & versions
- **Java**: **17 (LTS)**
- **Spring Boot**: **3.4.x**
- **Build**: **Maven only**
- **DB**: Postgres + Flyway
- **Tooling**: Docker, Testcontainers, PlantUML (optional), GitHub Actions/CI

---

## 2) Quickstart (Maven)

### 2.1 Minimal archetype (BOM + Modulith starters)
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
  <!-- JPA (if used): persistent publication registry -->
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-jpa</artifactId>
  </dependency>
  <!-- Modulith tests (verify, docs) -->
  <dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-test</artifactId>
    <scope>test</scope>
  </dependency>

  <!-- Boot + infra (examples) -->
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

### 2.b Spring Modulith Maven dependencies — **what and why**
| Artifact | Purpose | When to add | Notes |
|---|---|---|---|
| `spring-modulith-bom` | Centralize Modulith versions | **Always** | `${spring-modulith.version}` (e.g., 1.3.2) |
| `spring-modulith-starter-core` | Module discovery, `@ApplicationModule`, `@NamedInterface`, `verify()` | **Always** | Boundary foundation |
| `spring-modulith-starter-jpa` | Persistent publication registry | If **JPA** / registry | Enables restart republish |
| `spring-modulith-starter-jdbc` | Registry without JPA | If no JPA | Lightweight alternative |
| `spring-modulith-starter-mongodb` | Mongo‑based registry | If Mongo | Choose one backing store |
| `spring-modulith-starter-test` | `@ApplicationModuleTest`, `Documenter`, verify | **Test scope** | Generates docs |

**Useful properties (events)**
```properties
spring.modulith.events.republish-outstanding-events-on-restart=true
spring.modulith.events.completion-mode=UPDATE   # or DELETE, ARCHIVE
```

---

## 3) Module convention (e.g., `staticdata`)
```
com.crok4it.rgo.staticdata
 ├─ api/     # DTOs, ports (interfaces) @NamedInterface
 ├─ app/     # application services (use cases, transactions)
 ├─ domain/  # domain model, aggregates, invariants
 ├─ infra/   # adapters (JPA, external REST)
 ├─ web/     # thin REST controllers
 └─ config/  # module-local config
```

---

## 4) Annotations & boundaries
- Mark the root with `@ApplicationModule` (`package-info.java`).
- Mark **public API surface** with `@NamedInterface`.
```java
@org.springframework.modulith.NamedInterface
package com.crok4it.rgo.staticdata.api;
```
- **Rule:** depend on other modules **only** through their `api/` packages.

---

## 5) After‑commit events (outside `hit`)
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
  void on(StaticBlockUpdated evt) { /* async risk recompute */ }
}
```
**When?** ✅ for non‑critical reactions (risk recompute, search index). ❌ not inside `hit` (keep latency under control and flow simple).

---

## 6) Architectural verification (tests)
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

## 7) Per‑module integration tests
```java
@org.springframework.modulith.test.ApplicationModuleTest(verifyAutomatically = true)
class StaticDataModuleIT {
  @org.springframework.beans.factory.annotation.Autowired StaticDataUseCase useCase;
  @org.junit.jupiter.api.Test void creates_or_updates_static_block() { /* ... */ }
}
```

---

## 8) Living documentation (diagrams)
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
**Output:** `target/spring-modulith-docs/` (Maven). Publish as CI artifacts.

---

## 9) Per‑module observability
- **Actuator** (health, metrics, info)
- **Micrometer**: tag metrics by module
- **Correlated logs**: `requestId`, module name (MDC)
- **Dashboards**: errors/module, `hit` latency, listener backlogs

---

## 12) Initialization checklist
- [ ] Create project **Boot 3.4.x**, **Java 17**
- [ ] Add Modulith starters (core, jpa, test)
- [ ] Define **root package** and **modules** (see I)
- [ ] Add `@NamedInterface` for each module API
- [ ] Write **ModularityVerificationTest**
- [ ] Write **DocumentationTests**
- [ ] Implement **`hit` API** (idempotent + audit)
- [ ] Configure Flyway & Testcontainers
- [ ] Actuator + metrics + correlated logs
- [ ] CI: publish diagrams

---

## 13) Code conventions — with examples
- Structure: `api/app/domain/infra/web/config`; ports `*Port`, DTO `*Dto`/`*Cmd`, app services `*Service`.
- **DTOs** as records + validation (`@Valid` in web, business rules in domain).
- **Thin controllers** → call **ports**.
- **App services**: transactions & orchestration; publish events.
- **Pure domain**: invariants; no technical deps.
- **Infra**: JPA ≠ aggregates (map in infra).
- **Idempotency**: `requestId` + DB unique constraint; idempotent listeners.
- **Audit & logs**: audit per mutation; correlation with `requestId`.

---

## H) (reserved) Outbox & Retry — to be handled later

---

## I) RGO module breakdown — final cut

### I.1 Overview
| Module | Role & ownership | Tables | Public API | Events | Allowed deps |
|---|---|---|---|---|---|
| **customer** | SoR **Customer** & **Relations** | `customer`, `customer_relation` | `CustomerPort`, `/customers/*` | `CustomerChanged`, `CustomerRelationChanged` | `shared`, `audit` |
| **remediation-hit** | Accepts a **hit**, (re)starts a **review** (sync, idempotent) | `hit_request` | `HitPort`, `POST /api/hit` | `HitProcessed` (opt.) | ports → `remediation-review`, `blocks`, `customer`; `shared`, `audit` |
| **remediation-review** | Owns **Review** + **review_member** (association/snapshot) | `review`, `review_member`, `review_target` | `ReviewsPort`, `/reviews/*` | `ReviewInitialized`, `ReviewProgressed`, `ReviewClosed` | `shared`, `audit`; ports → `customer`, `blocks`, `documentary` |
| **blocks** | Single module for block types (KYC/KYT/Static/Doc…) | `block`, `block_type`, `block_state`, per‑type tables | `BlocksPort`, `/blocks/*` | `BlockCreated`, `BlockStateChanged` | `shared`, `audit` |
| **integration-salesforce** | Salesforce connector | — | `SalesforcePort`, callbacks | — | `shared`, `projections` |
| **audit** | Immutable audit + ELK export | `audit_event` | `AppendAuditEvent`, `AuditQuery` | `AuditAppended` | `shared` |
| **reliability** | Centralized error & retry | `event_retry_meta` (+ registry) | `RetryPolicy`, jobs | — | `shared` |
| **bdwh** | Data Platform gateway | — | `BdwhPort`, `/bdwh/*` | — | `shared` |
| **documentary** | Doc generation & tracking (DMS) | `generated_document`, `dms_link` | `DocumentaryPort`, `/documents/*` | `DocumentGenerated`, `DocumentUploaded` | `shared`, `audit` |
| **projections** | Read‑models (UI/reporting) | `read_model_*` | `ProjectionQuery` | — | events from all |

### I.2 Inside a module (rules)
```
com.crok4it.rgo.<module>
├─ api/      # Ports + public DTOs (@NamedInterface)
├─ app/      # Application services (use cases, transactions, after-commit events)
├─ domain/   # Aggregates, VOs, policies, invariants
├─ infra/    # JPA, external clients, mapping
├─ web/      # Thin REST controllers
└─ config/   # Module-local config
```

### I.3 API examples
```java
public interface CustomerPort { java.util.UUID register(RegisterCustomerCmd cmd); void linkRelation(java.util.UUID a, java.util.UUID b, RelationType type, String requestId); CustomerDto get(java.util.UUID id); }
public interface ReviewsPort { java.util.UUID startReview(StartReviewCmd cmd); void addReviewMember(java.util.UUID reviewId, java.util.UUID customerId, String role, String requestId); }
public interface BlocksPort { java.util.List<java.util.UUID> ensureBlocksForCustomer(java.util.UUID customerId, java.util.Set<BlockKind> kinds, String requestId); }
```

