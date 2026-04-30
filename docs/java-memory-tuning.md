# Java Memory Tuning — Spring Boot 3.4 / Java 17 in Containers

## Overview

This guide covers analysis and adjustment of JVM memory settings for a Spring Boot 3.4 application running on Java 17 inside a container (Docker / Kubernetes). Java 17 in a container context differs significantly from bare-metal tuning because the JVM must respect cgroup memory limits rather than host memory.

---

## 1. Understanding JVM Memory Regions

| Region | Flag | Description |
|---|---|---|
| Heap (Young + Old) | `-Xms` / `-Xmx` | Object allocation and GC |
| Metaspace | `-XX:MaxMetaspaceSize` | Class metadata, unlimited by default |
| Thread stacks | `-Xss` | Per-thread stack size (default 512 KB–1 MB) |
| Direct / off-heap | `-XX:MaxDirectMemorySize` | NIO buffers, Netty, etc. |
| Code cache | `-XX:ReservedCodeCacheSize` | JIT-compiled code |
| GC overhead | — | G1GC / ZGC internal buffers |

**Total container memory ≈ Heap + Metaspace + Thread stacks + Direct + Code cache + OS overhead**

---

## 2. Container-Awareness (Java 17)

Java 17 is fully container-aware by default. The flags below are **enabled automatically**:

```
-XX:+UseContainerSupport          # reads cgroup cpu/memory limits
-XX:InitialRAMPercentage=50.0     # heap starts at 50% of container limit
-XX:MaxRAMPercentage=75.0         # heap capped at 75% of container limit
-XX:MinRAMPercentage=50.0         # minimum heap for small containers (<200 MB)
```

> **Do not set `-Xmx` manually** when using percentage-based flags — they are mutually exclusive. Pick one strategy.

Verify the detected limits at startup:

```bash
java -XX:+PrintFlagsFinal -version 2>&1 | grep -E 'MaxRAM|InitialRAM|MaxHeap'
```

---

## 3. Recommended Baseline Configuration

### 3.1 Environment Variables (Docker / K8s)

```yaml
# docker-compose.yml
environment:
  JAVA_OPTS: >-
    -XX:InitialRAMPercentage=40.0
    -XX:MaxRAMPercentage=70.0
    -XX:MaxMetaspaceSize=256m
    -XX:ReservedCodeCacheSize=128m
    -Xss512k
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:HeapDumpPath=/tmp/heap-dump.hprof
    -XX:+ExitOnOutOfMemoryError
```

```yaml
# Kubernetes Deployment
resources:
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "1Gi"
    cpu: "1000m"
```

### 3.2 Spring Boot `application.properties` / `application.yml`

```yaml
# application.yml
spring:
  jvm:
    # Spring Boot 3.x uses these for actuator /info endpoint
    info:
      java:
        version: true
        vendor: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,heapdump,threaddump,prometheus
  endpoint:
    health:
      show-details: always
```

### 3.3 `Dockerfile` entrypoint

```dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY target/*.jar app.jar

# Pass JAVA_OPTS at runtime; avoid hardcoding heap sizes in the image
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

---

## 4. Sizing Guide by Container Memory Limit

| Container limit | `MaxRAMPercentage` | Effective heap | Metaspace | Stack budget |
|---|---|---|---|---|
| 512 MB | 70 % | ~358 MB | 128 MB | ~26 MB (52 threads × 512 KB) |
| 1 GB | 70 % | ~716 MB | 256 MB | ~52 MB |
| 2 GB | 70 % | ~1.4 GB | 256 MB | ~104 MB |
| 4 GB | 65 % | ~2.6 GB | 512 MB | ~256 MB |

**Rule of thumb**: reserve at least 20–30 % of the container limit for non-heap memory.

---

## 5. GC Selection

### G1GC (default, recommended for most workloads)

```
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=8m       # auto-tuned, set if > 4 GB heap
-XX:ConcGCThreads=2           # limit GC thread count in containers
-XX:ParallelGCThreads=4
```

### ZGC (low-latency APIs, heap > 1 GB)

```
-XX:+UseZGC
-XX:+ZGenerational            # Java 21+; use plain ZGC on Java 17
-XX:SoftMaxHeapSize=768m      # soft cap to trigger GC earlier
```

### Shenandoah (pause-less, OpenJDK builds)

```
-XX:+UseShenandoahGC
-XX:ShenandoahGCHeuristics=adaptive
```

---

## 6. Profiling and Analysis Steps

### Step 1 — Baseline metrics at startup

```bash
docker exec <container> java -XX:+PrintFlagsFinal -version 2>&1 \
  | grep -E 'MaxHeapSize|MaxMetaspaceSize|ThreadStackSize'
```

### Step 2 — Live heap via Spring Actuator

```bash
# Enable actuator endpoint in application.yml (see section 3.2)
curl http://localhost:8080/actuator/metrics/jvm.memory.used
curl http://localhost:8080/actuator/metrics/jvm.memory.max
curl http://localhost:8080/actuator/metrics/jvm.gc.pause
```

### Step 3 — JVM flags via jcmd inside the container

```bash
docker exec <container> jcmd 1 VM.flags
docker exec <container> jcmd 1 GC.heap_info
docker exec <container> jcmd 1 VM.native_memory summary
```

> `VM.native_memory` requires `-XX:NativeMemoryTracking=summary` to be set at startup.

### Step 4 — Heap dump analysis

Trigger on demand:

```bash
docker exec <container> jcmd 1 GC.heap_dump /tmp/heap.hprof
docker cp <container>:/tmp/heap.hprof ./heap.hprof
# Open with Eclipse MAT or VisualVM
```

Or via actuator:

```bash
curl -X GET http://localhost:8080/actuator/heapdump -o heap.hprof
```

### Step 5 — GC log analysis

Enable GC logging in `JAVA_OPTS`:

```
-Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=5,filesize=20m
```

Parse with [GCEasy](https://gceasy.io) or `jstat`:

```bash
docker exec <container> jstat -gcutil 1 5000 12
```

Output columns: `S0 S1 E O M CCS YGC YGCT FGC FGCT GCT`

---

## 7. Common Issues and Fixes

### OOMKilled (container killed, not JVM OOM)

**Symptom**: container exits with code 137, no heap dump.  
**Cause**: total process RSS exceeds container memory limit — non-heap memory is too large.  
**Fix**:

```
-XX:MaxMetaspaceSize=256m        # cap metaspace
-Xss256k                         # reduce stack size (test for StackOverflowError)
-XX:MaxDirectMemorySize=128m     # cap direct buffers
-XX:MaxRAMPercentage=60.0        # leave more headroom
```

### `java.lang.OutOfMemoryError: Java heap space`

**Cause**: heap exhausted.  
**Fix**: increase `MaxRAMPercentage` or container limit. Check for memory leaks with heap dump.

### `java.lang.OutOfMemoryError: Metaspace`

**Cause**: excessive class loading (reflection, Groovy, ByteBuddy, cglib proxies).  
**Fix**:

```
-XX:MaxMetaspaceSize=512m
-XX:MetaspaceSize=128m           # initial commit to avoid early GC
```

### High GC overhead

**Cause**: heap too small, object allocation rate too high.  
**Fix**:

```
-XX:MaxRAMPercentage=75.0
-XX:G1NewSizePercent=30          # increase young gen
-XX:G1MaxNewSizePercent=40
```

### Slow startup (Spring Boot + AOT)

Enable Spring AOT and CRaC checkpoint/restore for faster starts:

```bash
# Spring Boot 3.x — build with AOT
./mvnw spring-boot:build-image -Pnative
```

Or use Class Data Sharing:

```
-XX:+UseAppCDS
-XX:SharedArchiveFile=/tmp/app-cds.jsa
```

---

## 8. Native Memory Tracking (NMT)

Add to `JAVA_OPTS`:

```
-XX:NativeMemoryTracking=summary
```

Then query:

```bash
docker exec <container> jcmd 1 VM.native_memory summary scale=MB
```

Key output sections to watch:

- **Java Heap** — should match `-Xmx` or `MaxRAMPercentage`-derived value
- **Class** — metaspace
- **Thread** — `count × Xss`
- **Code** — JIT code cache
- **Internal** — various JVM internals

---

## 9. Prometheus Integration

### 9.1 Dependencies (Maven)

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <scope>runtime</scope>
</dependency>
```

### 9.2 Spring Boot configuration

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    prometheus:
      enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${spring.profiles.active:default}
    distribution:
      percentiles-histogram:
        http.server.requests: true
        jvm.gc.pause: true
      percentiles:
        http.server.requests: 0.5,0.90,0.95,0.99
        jvm.gc.pause: 0.5,0.90,0.99
    enable:
      jvm: true
      process: true
      system: true
      hikaricp: true        # connection pool metrics if using HikariCP
```

L'endpoint Prometheus est alors accessible sur : `GET /actuator/prometheus`

### 9.3 Métriques JVM clés exposées

| Métrique Prometheus | Description |
|---|---|
| `jvm_memory_used_bytes{area="heap"}` | Heap utilisé |
| `jvm_memory_max_bytes{area="heap"}` | Heap maximum |
| `jvm_memory_used_bytes{area="nonheap"}` | Metaspace + Code cache |
| `jvm_gc_pause_seconds_*` | Durée des pauses GC (count, sum, max, percentiles) |
| `jvm_gc_memory_promoted_bytes_total` | Promotions Old Gen |
| `jvm_gc_memory_allocated_bytes_total` | Allocations Young Gen |
| `jvm_threads_live_threads` | Threads actifs |
| `jvm_threads_daemon_threads` | Threads daemon |
| `process_cpu_usage` | CPU du process JVM |
| `process_memory_rss_bytes` | RSS total du process (non-heap inclus) |

### 9.4 Scrape config Prometheus

```yaml
# prometheus.yml
scrape_configs:
  - job_name: spring-boot-app
    scrape_interval: 15s
    scrape_timeout: 10s
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - app:8080   # nom du service Docker / K8s
    relabel_configs:
      - source_labels: [__address__]
        target_label: instance
```

### 9.5 Kubernetes — annotations de scrape automatique

Ajouter ces annotations sur le `Pod` ou le `Deployment` pour que l'opérateur Prometheus (kube-prometheus-stack) découvre automatiquement l'application :

```yaml
# deployment.yaml
spec:
  template:
    metadata:
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: "/actuator/prometheus"
        prometheus.io/port: "8080"
```

Ou via un `ServiceMonitor` si kube-prometheus-stack est installé :

```yaml
# service-monitor.yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: spring-boot-app
  labels:
    release: kube-prometheus-stack
spec:
  selector:
    matchLabels:
      app: spring-boot-app
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

### 9.6 Alertes Prometheus recommandées

```yaml
# alerts.yml
groups:
  - name: jvm-memory
    rules:
      - alert: HeapUsageHigh
        expr: |
          jvm_memory_used_bytes{area="heap"}
          / jvm_memory_max_bytes{area="heap"} > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Heap > 85 % sur {{ $labels.instance }}"

      - alert: HeapUsageCritical
        expr: |
          jvm_memory_used_bytes{area="heap"}
          / jvm_memory_max_bytes{area="heap"} > 0.95
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Heap > 95 % — risque OOM sur {{ $labels.instance }}"

      - alert: GCPauseHigh
        expr: |
          rate(jvm_gc_pause_seconds_sum[5m])
          / rate(jvm_gc_pause_seconds_count[5m]) > 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Pause GC moyenne > 500 ms sur {{ $labels.instance }}"

      - alert: MetaspaceUsageHigh
        expr: |
          jvm_memory_used_bytes{id="Metaspace"}
          / jvm_memory_max_bytes{id="Metaspace"} > 0.90
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Metaspace > 90 % sur {{ $labels.instance }}"
```

### 9.7 Dashboards Grafana

Importer les dashboards communautaires suivants (via l'ID Grafana) :

| Dashboard | ID | Description |
|---|---|---|
| JVM Micrometer | **4701** | Heap, GC, threads, CPU |
| Spring Boot Statistics | **6756** | HTTP, hikari, logback |
| JVM Overview (G1GC) | **11955** | Détail G1GC regions |

---

## 10. Kubernetes-Specific Recommendations

```yaml
# values.yaml / deployment patch
env:
  - name: JAVA_TOOL_OPTIONS
    value: >-
      -XX:MaxRAMPercentage=70.0
      -XX:InitialRAMPercentage=40.0
      -XX:MaxMetaspaceSize=256m
      -XX:+ExitOnOutOfMemoryError
      -XX:+HeapDumpOnOutOfMemoryError
      -XX:HeapDumpPath=/tmp

# Liveness / readiness aligned with JVM warm-up
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 20
  periodSeconds: 5
```

Use `JAVA_TOOL_OPTIONS` (not `JAVA_OPTS`) in Kubernetes — it is read by all JVM processes including tools like `jcmd`.

---

## 10. Quick-Reference Checklist

- [ ] Container memory `limits` set in Kubernetes / Docker
- [ ] `MaxRAMPercentage` used instead of hard-coded `-Xmx`
- [ ] `MaxMetaspaceSize` explicitly capped
- [ ] GC logging enabled in staging/production
- [ ] Dépendance `micrometer-registry-prometheus` présente dans `pom.xml`
- [ ] Endpoint `/actuator/prometheus` exposé et accessible
- [ ] Annotations de scrape ou `ServiceMonitor` configurés
- [ ] Alertes `HeapUsageHigh` et `GCPauseHigh` actives
- [ ] Dashboard Grafana ID 4701 importé
- [ ] `ExitOnOutOfMemoryError` enabled to trigger pod restart
- [ ] `HeapDumpOnOutOfMemoryError` with persistent volume for dump path
- [ ] NMT enabled in staging for baseline RSS measurement
- [ ] Load test with realistic throughput before sizing for production
