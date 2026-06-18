# 레거시 Eventuate Tram 샘플 + 라이브 E2E 구현 계획 (Spec 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spec 2(sleuth trace-mode, PR #60)의 라이브 수용 게이트 — Java8/Boot2/H5 + Sleuth(B3) + Eventuate Tram(Kafka+MySQL binlog CDC) 3서비스(A →HTTP→ B →Tram→ C) 자급 docker-compose 샘플과, 주입 B3 trace-id가 비동기 Tram 경계를 넘어 C의 H5 SQL 로그에 동일 값으로 찍히는지(R1) + 빌더 attach 캡처를 실증하는 로컬 E2E 런북을 만든다. 빌더 `SchemaExtractor`의 MySQL 미지원을 선행 보정한다.

**Architecture:** 빌더 보정(SchemaExtractor가 DB product별 catalog/schema 선택) → samples/legacy-tram/ 아래 3개 독립 Java8 Gradle 서비스(order-web/reservation/ledger) + Eventuate Tram 발행/구독 + Sleuth B3 전파(1순위 통합 모듈, 폴백 MessageInterceptor) → docker-compose(kafka/mysql-binlog/eventuate-cdc-service/3앱) → E2E 런북(빌더 sleuth 지원 fail-fast 점검 → up → R1·캡처·노이즈배제 검증 → down).

**Tech Stack:** 빌더: Java 17/Gradle/JUnit5/AssertJ/Testcontainers(MySQL). 샘플: Java 8 + Spring Boot 2.7.x + Hibernate 5 + Spring Cloud Sleuth(Brave/B3) + Eventuate Tram + Spring Kafka client. 인프라: docker compose, MySQL 8(binlog ROW), Kafka, `eventuateio/eventuate-cdc-service`.

## Global Constraints

(스펙 `docs/superpowers/specs/2026-06-18-legacy-tram-sample-e2e-design.md` 에서 인용)

- **DB = MySQL (binlog CDC)**. 비즈니스 테이블은 Hibernate `ddl-auto=update`로 생성; Eventuate `message`/`received_messages`는 **1순위 init.sql + 필수 폴백 JPA `@Entity` 미러**(ddl-auto=update가 없으면 생성·있으면 no-op; 핀 버전 스키마와 정확히 일치).
- **상관 헤더는 B3**(Spec 2 §7: sleuth 모드는 `X-B3-TraceId/SpanId/Sampled` + `b3` 주입, traceparent 미사용). builder가 B3를 주입·상관.
- **R1 전파 1순위** = `io.eventuate.tram.springcloudsleuth:eventuate-tram-spring-cloud-sleuth-tram-starter:0.5.0.RELEASE` 의존성(**Task 2 정정**: 구 `eventuate-tram-spring-cloud-sleuth-integration`은 Boot 2.7용 미존재 — 0.29.0/Boot≤2.5 이후 단종); **폴백** = 커스텀 `io.eventuate.tram.messaging.common.MessageInterceptor`(발행측 현재 Brave span→메시지 B3 헤더, 수신측 헤더 extract→nextSpan→SpanInScope). 둘 다로도 안 되면 **R1=거짓** 판정·문서화.
- **확정 버전 매트릭스(Task 2 스파이크 — 정본 `samples/legacy-tram/VERSIONS.md`)**: Java 8, Spring Boot 2.7.18, Spring Cloud 2021.0.8(sleuth 3.1.9), Eventuate Tram core 0.35.0.RELEASE, sleuth 통합 `eventuate-tram-spring-cloud-sleuth-tram-starter:0.5.0.RELEASE`, `eventuateio/eventuate-cdc-service:0.17.0.RELEASE`, mysql connector 8.0.28(BOM), MySQL 8.0. 모든 서비스 build는 이 값을 사용.
- **logback**: `%X{traceId}` 포함 + 로거명·메시지 표준 ` : ` 구분자(Spring Boot 기본).
- **설정 제약**: 샘플 서비스는 앱 설정을 **개별 env**(`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_JPA_HIBERNATE_DDL_AUTO`[underscore — Spring relaxed binding이 `spring.jpa.hibernate.ddl-auto`에 매핑] 등)로 — 빌더 override가 `SPRING_APPLICATION_JSON`을 교체하므로 거기에 앱 설정을 의존하지 말 것.
- **A 성공 상태코드 = HTTP 202**(C SQL 미완 — 비동기).
- **CDC 기동**: `ledger(C).depends_on=[eventuate-cdc-service]`, `eventuate-cdc-service.depends_on=[mysql(healthy),kafka(healthy)]` → 빌더 `up --wait <capture-services>`가 CDC를 끌어올림.
- **빌더 의존성(런북 사전조건)**: PR #60 빌더(`--trace-mode`/`--capture-services`) + 본 계획 Task 1(SchemaExtractor MySQL). 런북이 fail-fast 점검.
- **메인 Gradle 미포함**: 샘플 3서비스는 각자 Java8 toolchain 독립 Gradle 빌드(루트 settings.gradle.kts에 추가하지 않음).
- **CI 미포함**: 로컬 런북이 수용 게이트.

**빌드/테스트 명령:**
- 빌더 단위테스트: `./gradlew :graph-rag-builder:test --tests '<FQCN>' --console=plain`
- 샘플 서비스 빌드: `(cd samples/legacy-tram/<svc> && ./gradlew bootJar)` 또는 compose 빌드.
- E2E: `bash e2e/run-legacy-tram-sleuth-e2e.sh`

---

## File Structure

**빌더 보정(Phase 1):**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/schema/SchemaExtractor.java` — DB product별 catalog/schema 선택.
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/schema/SchemaExtractorMySqlTest.java`(신규, MySQL testcontainer) + `...SchemaExtractorPostgresTest.java`(회귀, Postgres testcontainer; 기존 동작 고정).

**샘플 공통(Phase 2):**
- Create: `samples/legacy-tram/gradle/libs.versions.toml`(또는 각 서비스 build에 버전 인라인) — 핀된 Eventuate/Boot/Sleuth 버전.
- Create: `samples/legacy-tram/common/` (선택) — 공유 `OrderReserved` 이벤트 클래스. (각 서비스가 복제해도 됨 — 독립 빌드 단순화 위해 **복제** 채택.)

**서비스 A `order-web` (Phase 3):**
- `samples/legacy-tram/order-web/build.gradle.kts`, `settings.gradle.kts`, `gradlew`(wrapper), `Dockerfile`
- `.../src/main/java/sample/order/OrderWebApplication.java`, `Order.java`(@Entity), `OrderRepository.java`, `OrderController.java`(POST /orders → save + RestTemplate POST B /reservations → 202)
- `.../src/main/resources/application.yml`, `logback-spring.xml`(%X{traceId} + ` : `)

**서비스 B `reservation` (Phase 4):**
- build/settings/wrapper/Dockerfile
- `Reservation.java`, `ReservationRepository.java`, `ReservationController.java`(POST /reservations → save + DomainEventPublisher.publish OrderReserved, 같은 TX)
- `OrderReserved.java`(DomainEvent), `TramMessagingConfig.java`(@Import publisher config + sleuth integration), `EventuateMessageEntity.java`(폴백 JPA 미러: `message`), `B3MessageInterceptor.java`(폴백, 조건부 빈)
- `application.yml`, `logback-spring.xml`

**서비스 C `ledger` (Phase 5):**
- build/settings/wrapper/Dockerfile
- `LedgerEntry.java`, `LedgerEntryRepository.java`, `OrderReserved.java`(복제), `OrderEventHandlers.java`(@EventHandler → received_messages dedup은 Eventuate가 처리 + ledger_entries insert), `TramSubscriberConfig.java`, `EventuateReceivedMessagesEntity.java`(폴백 JPA 미러: `received_messages`), `B3MessageInterceptor.java`(폴백)
- `application.yml`, `logback-spring.xml`

**인프라(Phase 6):**
- `samples/legacy-tram/docker-compose.yml`, `samples/legacy-tram/mysql/init.sql`(Eventuate 공식 스키마, 3 DB), `samples/legacy-tram/mysql/my.cnf`(binlog), `samples/legacy-tram/README.md`

**E2E(Phase 7):**
- `e2e/run-legacy-tram-sleuth-e2e.sh`, `e2e/request-legacy-orders.json`

---

## Task 1: 빌더 SchemaExtractor — MySQL/MariaDB 스키마 되읽기 지원

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/schema/SchemaExtractor.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/schema/SchemaExtractorMySqlTest.java`

**Interfaces:**
- Consumes: `Connection`(JDBC), `io.graphrag.model.TableSchema`/`ColumnSchema`.
- Produces: `SchemaExtractor.extract(Connection)` 가 **DB product를 자동 감지**(MySQL/MariaDB→catalog=`connection.getCatalog()`, schema=null; 그 외→catalog=null, schema="public")해 양쪽 모두 테이블을 반환. 시그니처 불변(caller 변경 없음).

현재 `getTables(null, "public", ...)`의 `"public"` 하드코딩이 MySQL에서 0 테이블을 만든다. product 감지로 양쪽 지원.

- [ ] **Step 1: Write the failing test (MySQL testcontainer)**

`SchemaExtractorMySqlTest.java`:

```java
package io.graphrag.builder.schema;

import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaExtractorMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("orderdb");

    @Test
    void extractsTablesColumnsAndPkFromMySql() throws Exception {
        try (Connection c = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                    + "user_id VARCHAR(64) NOT NULL, amount INT NULL)");

            List<TableSchema> tables = new SchemaExtractor().extract(c);

            assertThat(tables).extracting(TableSchema::name).contains("orders");
            TableSchema orders = tables.stream()
                    .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
            assertThat(orders.columns()).extracting("name")
                    .contains("id", "user_id", "amount");
            assertThat(orders.columns().stream()
                    .filter(col -> col.name().equals("id")).findFirst().orElseThrow().primaryKey())
                    .isTrue();
        }
    }
}
```

- [ ] **Step 1b: Add the Testcontainers JUnit5 extension to builder test deps (리뷰 Sonnet I1)**

`@Testcontainers`/`@Container`는 `org.testcontainers:junit-jupiter`(alias `libs.testcontainers.junit`, 카탈로그에 이미 존재)가 필요하나 `graph-rag-builder/build.gradle.kts`의 test 스코프에 없다. 추가:
```kotlin
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers.junit)   // @Testcontainers/@Container 확장
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.schema.SchemaExtractorMySqlTest' --console=plain`
Expected: FAIL — `tables`가 비어 `contains("orders")` 실패(현재 `"public"` 하드코딩으로 0 테이블). (`build.gradle.kts`에 testcontainers.junit 추가 후 컴파일됨.)

- [ ] **Step 3: Implement product-aware catalog/schema selection**

`SchemaExtractor.java` 전체를 다음으로 교체(시그니처 불변, catalog/schema를 product로 결정해 모든 메타데이터 호출에 전달):

```java
package io.graphrag.builder.schema;

import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.TableSchema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** JDBC DatabaseMetaData → 물리 스키마 사실 (운영 동일 DBMS 기준, docs/03 L2). */
public class SchemaExtractor {

    public List<TableSchema> extract(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        // MySQL/MariaDB는 catalog=database, schema=null; Postgres 등은 catalog=null, schema="public".
        String product = meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
        boolean mysqlFamily = product.contains("mysql") || product.contains("mariadb");
        String catalog = mysqlFamily ? connection.getCatalog() : null;
        String schema = mysqlFamily ? null : "public";

        List<TableSchema> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(extractTable(meta, catalog, schema, rs.getString("TABLE_NAME")));
            }
        }
        tables.sort((a, b) -> a.name().compareTo(b.name()));
        return tables;
    }

    private TableSchema extractTable(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        Set<String> primaryKeys = new LinkedHashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        List<ColumnSchema> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                columns.add(new ColumnSchema(
                        name,
                        rs.getString("TYPE_NAME").toUpperCase(),
                        "YES".equals(rs.getString("IS_NULLABLE")),
                        primaryKeys.contains(name),
                        "YES".equals(rs.getString("IS_AUTOINCREMENT"))));
            }
        }

        List<ForeignKey> foreignKeys = new ArrayList<>();
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                foreignKeys.add(new ForeignKey(
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME")));
            }
        }

        Map<String, List<String>> uniqueIndexes = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, true, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (indexName != null && column != null) {
                    uniqueIndexes.computeIfAbsent(indexName, k -> new ArrayList<>()).add(column);
                }
            }
        }

        return new TableSchema(table, columns, foreignKeys,
                new ArrayList<>(uniqueIndexes.values()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes + Postgres regression**

먼저 회귀 테스트 추가 `SchemaExtractorPostgresTest.java`(기존 Postgres 동작이 안 깨졌음을 고정):

```java
package io.graphrag.builder.schema;

import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaExtractorPostgresTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    @Test
    void extractsTablesFromPostgresPublicSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGSERIAL PRIMARY KEY, user_id VARCHAR(64) NOT NULL)");
            List<TableSchema> tables = new SchemaExtractor().extract(c);
            assertThat(tables).extracting(TableSchema::name).contains("orders");
        }
    }
}
```

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.schema.SchemaExtractor*Test' --console=plain`
Expected: PASS (MySQL + Postgres 둘 다). (Docker 필요 — testcontainers.)

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/build.gradle.kts \
        graph-rag-builder/src/main/java/io/graphrag/builder/schema/SchemaExtractor.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/schema/SchemaExtractorMySqlTest.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/schema/SchemaExtractorPostgresTest.java
git commit -m "feat(builder): SchemaExtractor reads MySQL/MariaDB schema via catalog (was Postgres-only)"
```

> **빌더 반영 경로**: 이 보정은 빌더에 속한다. 본 worktree(main 기준)에 커밋하되, 최종적으로 빌더 메인라인(PR #60 머지 후 main, 또는 별도 builder PR)에 반영되어야 런북이 쓰는 빌더에 포함된다. 런북은 §Task 8에서 이 지원을 사전점검한다.

---

## Task 2: Eventuate 버전 핀 스파이크 + 샘플 빌드 관례 확정

**Files:**
- Create: `samples/legacy-tram/VERSIONS.md`(핀 매트릭스 기록)
- Create: `samples/legacy-tram/reservation/` 와 `ledger/` 의 최소 build.gradle.kts 골격(Eventuate 의존성 해소 확인용; Phase 4/5에서 살 채움)

**Interfaces:**
- Produces: 호환 검증된 버전 핀 — `BOOT_VERSION`(2.7.x 구체), `SPRING_CLOUD_VERSION`(Sleuth 포함 train, 예: 2021.0.x), `EVENTUATE_TRAM_VERSION`(예: `0.33.0.RELEASE` 계열), `EVENTUATE_CDC_IMAGE`(예: `eventuateio/eventuate-cdc-service:0.16.0.RELEASE` 계열), `JAVA=8`. 이후 모든 서비스 build가 이 값을 사용.

이 task의 목적은 **Eventuate Tram ↔ Boot2.7 ↔ Java8 ↔ sleuth-integration ↔ cdc 이미지**의 실제 호환 조합을 부팅으로 확정하는 것(스펙 R: 버전 정합성). 추측 금지 — 의존성 해소가 깨지면 인접 버전으로 조정해 기록한다.

- [ ] **Step 1: 최소 Boot2.7/Java8 프로젝트로 Eventuate Tram 의존성 해소 확인**

`samples/legacy-tram/reservation/build.gradle.kts`(골격):

```kotlin
plugins {
    java
    id("org.springframework.boot") version "2.7.18"
    id("io.spring.dependency-management") version "1.0.15.RELEASE"
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }
repositories { mavenCentral() }
extra["springCloudVersion"] = "2021.0.8"
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.cloud:spring-cloud-starter-sleuth")
    implementation("io.eventuate.tram.core:eventuate-tram-spring-jdbc-kafka:0.33.0.RELEASE")
    implementation("io.eventuate.tram.core:eventuate-tram-spring-events:0.33.0.RELEASE")
    implementation("io.eventuate.tram.core:eventuate-tram-spring-cloud-sleuth-integration:0.33.0.RELEASE")
    runtimeOnly("mysql:mysql-connector-java")   // Boot 2.7 BOM이 관리하는 좌표(신 com.mysql:* 은 Boot3+; 리뷰 Sonnet I3 반려)
}
dependencyManagement {
    imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}") }
}
```

`samples/legacy-tram/reservation/settings.gradle.kts`: `rootProject.name = "reservation"`

- [ ] **Step 2: 의존성 해소 + 컴파일 확인 (부팅 스파이크)**

Run:
```bash
cd samples/legacy-tram/reservation && gradle dependencies --configuration runtimeClasspath -q | grep -iE 'eventuate|sleuth|boot' | head
```
Expected: 모든 좌표가 해소됨(충돌·미해결 없음). 해소 실패 시 인접 버전으로 조정(예: Eventuate Tram 버전을 그 버전이 요구하는 Boot/Spring Cloud와 맞춤)하고 **확정값을 기록**.

> 정확한 호환 버전은 환경 의존이라 위 값은 출발점이다. **해소되는 실제 조합을 찾는 것이 이 task의 산출물**이다. (Eventuate Tram 릴리스 노트/`eventuate-tram-core` BOM과 Boot2.7/SpringCloud 2021.0.x 호환을 맞춘다.)

- [ ] **Step 3: 확정 버전을 VERSIONS.md에 기록**

`samples/legacy-tram/VERSIONS.md`:

```markdown
# Pinned versions (검증: gradle dependencies 해소 + 컨테이너 부팅)
- Java: 8
- Spring Boot: <확정값, 예 2.7.18>
- Spring Cloud (Sleuth): <확정값, 예 2021.0.8>
- Eventuate Tram core: <확정값, 예 0.33.0.RELEASE>
- eventuate-tram-spring-cloud-sleuth-integration: <Eventuate Tram core와 동일>
- eventuate-cdc-service image: <확정 태그>
- MySQL: 8.0 (binlog ROW)
```

- [ ] **Step 4: Commit**

```bash
git add samples/legacy-tram/VERSIONS.md samples/legacy-tram/reservation/build.gradle.kts \
        samples/legacy-tram/reservation/settings.gradle.kts
git commit -m "chore(sample): pin compatible Boot2.7/Java8/Eventuate Tram/CDC versions"
```

---

## Task 3: 서비스 A `order-web` (HTTP entry, orders insert, →B 동기 호출)

**Files:**
- Create: `samples/legacy-tram/order-web/build.gradle.kts`, `settings.gradle.kts`, `Dockerfile`
- Create: `.../src/main/java/sample/order/OrderWebApplication.java`, `Order.java`, `OrderRepository.java`, `OrderController.java`
- Create: `.../src/main/resources/application.yml`, `logback-spring.xml`

**Interfaces:**
- Produces: HTTP `POST /orders {userId:String, amount:int}` → `orders`(id,user_id,amount,created_at) insert → `RestTemplate` POST `http://reservation:8080/reservations {orderId,userId,amount}` → **HTTP 202**. health `/actuator/health`.
- Consumes(런타임): B의 `POST /reservations`(Task 4).

- [ ] **Step 1: build.gradle.kts + settings + Application**

`build.gradle.kts`(버전은 Task 2 확정값):
```kotlin
plugins {
    java
    id("org.springframework.boot") version "2.7.18"
    id("io.spring.dependency-management") version "1.0.15.RELEASE"
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }
repositories { mavenCentral() }
extra["springCloudVersion"] = "2021.0.8"
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.cloud:spring-cloud-starter-sleuth")
    runtimeOnly("mysql:mysql-connector-java")   // Boot 2.7 BOM이 관리하는 좌표(신 com.mysql:* 은 Boot3+; 리뷰 Sonnet I3 반려)
}
dependencyManagement {
    imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}") }
}
```
`settings.gradle.kts`: `rootProject.name = "order-web"`

`OrderWebApplication.java`:
```java
package sample.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class OrderWebApplication {
    public static void main(String[] args) { SpringApplication.run(OrderWebApplication.class, args); }
    @Bean RestTemplate restTemplate() { return new RestTemplate(); }
}
```

- [ ] **Step 2: Entity + Repository + Controller**

`Order.java`:
```java
package sample.order;

import javax.persistence.*;

@Entity @Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private String userId;
    @Column(name = "amount") private int amount;
    @Column(name = "created_at") private Long createdAt = System.currentTimeMillis();
    protected Order() {}
    public Order(String userId, int amount) { this.userId = userId; this.amount = amount; }
    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public int getAmount() { return amount; }
}
```

`OrderRepository.java`:
```java
package sample.order;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrderRepository extends JpaRepository<Order, Long> {}
```

`OrderController.java`:
```java
package sample.order;

import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
public class OrderController {
    private final OrderRepository orders;
    private final RestTemplate http;
    private final String reservationUrl;

    public OrderController(OrderRepository orders, RestTemplate http,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${reservation.url:http://reservation:8080}") String reservationUrl) {
        this.orders = orders; this.http = http; this.reservationUrl = reservationUrl;
    }

    @PostMapping("/orders")
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.get("userId"));
        int amount = ((Number) body.getOrDefault("amount", 0)).intValue();
        Order saved = orders.save(new Order(userId, amount));        // H5 SQL @A
        // 동기 HTTP → B (Sleuth가 B3 전파). 응답 시점엔 C(Tram) 미완 → 202.
        // Java 8: Map.of(Java9+) 미사용 — LinkedHashMap 사용.
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("orderId", saved.getId()); req.put("userId", userId); req.put("amount", amount);
        http.postForEntity(reservationUrl + "/reservations", req, Void.class);
        return ResponseEntity.accepted().body(
                Collections.singletonMap("orderId", saved.getId()));
    }
}
// import java.util.Collections; java.util.LinkedHashMap; java.util.Map;
```

- [ ] **Step 3: application.yml + logback (%X{traceId} + ` : `)**

`application.yml`:
```yaml
server:
  port: 8080
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://mysql:3306/orderdb}
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:rootpw}
  jpa:
    hibernate:
      ddl-auto: ${SPRING_JPA_HIBERNATE_DDL_AUTO:update}
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
reservation:
  url: ${RESERVATION_URL:http://reservation:8080}
management:
  endpoints:
    web:
      exposure:
        include: health
```

`logback-spring.xml`(traceId MDC + 표준 ` : ` 구분자):
```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <charset>UTF-8</charset>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{traceId:-},%X{spanId:-}] %logger{40} : %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO"><appender-ref ref="STDOUT"/></root>
</configuration>
```

- [ ] **Step 4: Dockerfile + 빌드 확인**

`Dockerfile`(멀티스테이지 — 이미지 안에서 빌드해 `docker compose --build`가 호스트 사전빌드/wrapper 없이 자체완결; 리뷰 Sonnet I13). reservation/ledger도 동일 패턴:
```dockerfile
FROM gradle:7.6-jdk8 AS build
WORKDIR /src
COPY . .
RUN gradle bootJar --no-daemon
FROM eclipse-temurin:8-jre
COPY --from=build /src/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

Run(로컬 컴파일 확인은 시스템 gradle 또는 도커 빌드로):
`docker build -t order-web-check samples/legacy-tram/order-web`
Expected: 빌드 성공(멀티스테이지로 jar 생성·이미지 완성). (DB 없이도 빌드 성공; 부팅 검증은 compose 단계.)

- [ ] **Step 5: Commit**

```bash
git add samples/legacy-tram/order-web
git commit -m "feat(sample): order-web (A) HTTP entry — orders insert + sync call to B, 202"
```

---

## Task 4: 서비스 B `reservation` (reservations insert + Tram 발행 + B3 전파)

**Files:**
- Create: `samples/legacy-tram/reservation/{build.gradle.kts(Task2),settings.gradle.kts,Dockerfile}`
- Create: `.../src/main/java/sample/reservation/{ReservationApplication,Reservation,ReservationRepository,ReservationController,OrderReserved,TramMessagingConfig,EventuateMessageEntity,B3MessageInterceptor}.java`
- Create: `.../src/main/resources/{application.yml,logback-spring.xml}`

**Interfaces:**
- Consumes: A의 `POST /reservations {orderId,userId,amount}`.
- Produces: `reservations` insert + `OrderReserved{orderId,userId,amount}` Tram 도메인 이벤트(aggregateType=`Order`, aggregateId=orderId) 발행(같은 TX → outbox `message` insert). 이벤트 클래스 `sample.reservation.OrderReserved`(C가 동일 FQCN 또는 동일 JSON으로 구독 — **type 매핑은 §주의** 참조).

- [ ] **Step 1: Application + Tram 발행 설정 + 이벤트**

`ReservationApplication.java`:
```java
package sample.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReservationApplication {
    public static void main(String[] args) { SpringApplication.run(ReservationApplication.class, args); }
}
```

`OrderReserved.java`(DomainEvent):
```java
package sample.reservation;

import io.eventuate.tram.events.common.DomainEvent;

// NOTE(구현 정정): eventuate-tram-events:0.35.0.RELEASE 에는 @EventType 가 존재하지 않는다(jar 검증:
// DomainEvent/DomainEventNameMapping/DefaultDomainEventNameMapping 만 존재, DefaultDomainEventNameMapping 은
// FQCN(getClass().getName())으로 라우팅). 따라서 발행 event-type 헤더 = FQCN `sample.reservation.OrderReserved`.
// 라우팅 일치는 C(ledger)가 동일 FQCN(package sample.reservation)으로 OrderReserved 를 복제해 맞춘다(§주의).
public class OrderReserved implements DomainEvent {
    private Long orderId; private String userId; private int amount;
    public OrderReserved() {}
    public OrderReserved(Long orderId, String userId, int amount) {
        this.orderId = orderId; this.userId = userId; this.amount = amount;
    }
    public Long getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public int getAmount() { return amount; }
}
```

`TramMessagingConfig.java`(발행 인프라 + sleuth 통합 활성):
```java
package sample.reservation;

import io.eventuate.tram.spring.events.publisher.TramEventsPublisherConfiguration;
import io.eventuate.tram.spring.messaging.producer.jdbc.TramMessageProducerJdbcConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({TramMessageProducerJdbcConfiguration.class, TramEventsPublisherConfiguration.class})
public class TramMessagingConfig {
    // eventuate-tram-spring-cloud-sleuth-tram-starter(Task 2 확정 좌표)가 클래스패스에 있으면 자동 구성으로 B3가 메시지에 전파(1순위).
}
```

- [ ] **Step 2: Entity + Repository + Controller (insert + publish, 같은 TX)**

`Reservation.java`:
```java
package sample.reservation;

import javax.persistence.*;

@Entity @Table(name = "reservations")
public class Reservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "order_id") private Long orderId;
    @Column(name = "user_id") private String userId;
    @Column(name = "amount") private int amount;
    @Column(name = "created_at") private Long createdAt = System.currentTimeMillis();
    protected Reservation() {}
    public Reservation(Long orderId, String userId, int amount) {
        this.orderId = orderId; this.userId = userId; this.amount = amount;
    }
    public Long getId() { return id; }
}
```

`ReservationRepository.java`:
```java
package sample.reservation;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReservationRepository extends JpaRepository<Reservation, Long> {}
```

`ReservationController.java`:
```java
package sample.reservation;

import io.eventuate.tram.events.publisher.DomainEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
public class ReservationController {
    private final ReservationRepository reservations;
    private final DomainEventPublisher eventPublisher;

    public ReservationController(ReservationRepository reservations, DomainEventPublisher eventPublisher) {
        this.reservations = reservations; this.eventPublisher = eventPublisher;
    }

    @PostMapping("/reservations")
    @Transactional
    public ResponseEntity<Void> create(@RequestBody Map<String, Object> body) {
        Long orderId = ((Number) body.get("orderId")).longValue();
        String userId = String.valueOf(body.get("userId"));
        int amount = ((Number) body.getOrDefault("amount", 0)).intValue();
        reservations.save(new Reservation(orderId, userId, amount));     // H5 SQL @B
        // 같은 TX에서 outbox(message) insert — Eventuate 트랜잭셔널 아웃박스. trace 컨텍스트 보존.
        eventPublisher.publish("Order", String.valueOf(orderId),
                Collections.singletonList(new OrderReserved(orderId, userId, amount)));
        return ResponseEntity.accepted().build();
    }
}
```

- [ ] **Step 3: Eventuate `message` 테이블 JPA 폴백 + B3 폴백 인터셉터**

`EventuateMessageEntity.java`(폴백 — ddl-auto=update가 `message` 테이블을 생성. **컬럼은 핀 버전 Eventuate 스키마와 일치**; 아래는 Eventuate Tram MySQL 스키마 기준, Task 2 핀 버전에서 검증·조정):
```java
package sample.reservation;

import javax.persistence.*;

/** Eventuate Tram 'message' 아웃박스 테이블의 JPA 미러(폴백 생성용). init.sql 부재/실패 시 ddl-auto=update가 생성.
 *  컬럼 정의는 핀 버전 Eventuate 공식 스키마와 정확히 일치해야 한다(불일치 시 Eventuate insert 실패). */
@Entity @Table(name = "message")
public class EventuateMessageEntity {
    @Id @Column(name = "id", length = 255) private String id;                  // init.sql과 동일(255, InnoDB 한계)
    @Column(name = "destination", length = 1000) private String destination;
    @Lob @Column(name = "headers", columnDefinition = "LONGTEXT") private String headers;   // init.sql과 동일
    @Lob @Column(name = "payload", columnDefinition = "LONGTEXT") private String payload;
    @Column(name = "published", columnDefinition = "SMALLINT") private Short published;
    @Column(name = "message_partition", columnDefinition = "SMALLINT") private Short messagePartition; // 리뷰 Gemini I1
    @Column(name = "creation_time", columnDefinition = "BIGINT") private Long creationTime;
    protected EventuateMessageEntity() {}
}
```
> **주의**: 위 컬럼은 출발점이다. Task 2 핀 버전의 `eventuate-tram-*` 공식 스키마(`mysql/init.sql` Task 6)와 **정확히 대조**해 타입/길이(특히 `published`, `message_partition` 등 버전별 추가 컬럼)를 맞춘다. 폴백 엔티티와 init.sql DDL은 동일 스키마를 표현해야 한다.

`B3MessageInterceptor.java`(폴백 — 1순위 sleuth-integration이 동작하면 불필요. `eventuate.b3.fallback=true`일 때만 활성):
```java
package sample.reservation;

import brave.Tracer;
import brave.Tracing;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 폴백: 발행 메시지에 현재 Brave span의 B3를 헤더로 복사(수신측은 ledger의 동명 인터셉터가 복원). */
@Component
@ConditionalOnProperty(name = "eventuate.b3.fallback", havingValue = "true")
public class B3MessageInterceptor implements MessageInterceptor {
    private final Tracing tracing;
    public B3MessageInterceptor(Tracing tracing) { this.tracing = tracing; }

    @Override public void preSend(Message message) {
        Tracer tracer = tracing.tracer();
        brave.Span span = tracer.currentSpan();
        if (span != null) {
            String traceId = span.context().traceIdString();
            String spanId = span.context().spanIdString();
            message.setHeader("X-B3-TraceId", traceId);
            message.setHeader("X-B3-SpanId", spanId);
            message.setHeader("X-B3-Sampled", "1");
        }
    }
}
```

- [ ] **Step 4: application.yml + logback + Dockerfile + 빌드 확인**

`application.yml`:
```yaml
server: { port: 8080 }
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://mysql:3306/reservationdb}
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:rootpw}
  jpa:
    hibernate: { ddl-auto: ${SPRING_JPA_HIBERNATE_DDL_AUTO:update} }
eventuate:
  b3:
    fallback: ${EVENTUATE_B3_FALLBACK:false}
management: { endpoints: { web: { exposure: { include: health } } } }
# Eventuate Kafka/JDBC: eventuatelocal.kafka.bootstrap.servers 등은 env로 주입(compose에서).
# 주(Task 7 실증 정정): 원안(리뷰 Sonnet I5/Gemini I2)은 "eventuate.database.schema 미설정, 테이블은 reservationdb"
# 였으나 — Eventuate 0.35.0/CDC 0.17.0 은 인프라 테이블(message/received_messages/offset_store/cdc_monitoring)을
# 기본 schema 'eventuate' 에 둔다. Task 7 E2E(R1 PASS = end-to-end 실증)로 확정: init.sql 이 별도 'eventuate' DB를
# 만들고, CDC datasource=jdbc:mysql://mysql:3306/eventuate, app/cdc 유저에 eventuate.* 권한. (원안 가정은 틀렸음.)
```
(logback-spring.xml = Task 3과 동일 내용. Dockerfile = Task 3과 동일.)

Run: `cd samples/legacy-tram/reservation && gradle bootJar -q && ls build/libs/*.jar`
Expected: jar 생성.

- [ ] **Step 5: Commit**

```bash
git add samples/legacy-tram/reservation
git commit -m "feat(sample): reservation (B) — insert + Tram OrderReserved publish (same TX) + B3 wiring"
```

---

## Task 5: 서비스 C `ledger` (Tram 구독 + ledger_entries insert = 타깃 비동기 H5 SQL)

**Files:**
- Create: `samples/legacy-tram/ledger/{build.gradle.kts,settings.gradle.kts,Dockerfile}`
- Create: `.../src/main/java/sample/ledger/{LedgerApplication,LedgerEntry,LedgerEntryRepository,OrderReserved,OrderEventHandlers,TramSubscriberConfig,EventuateReceivedMessagesEntity,B3MessageInterceptor}.java`
- Create: `.../src/main/resources/{application.yml,logback-spring.xml}`

**Interfaces:**
- Consumes: B가 발행한 `OrderReserved`(aggregateType=`Order`). **type 매핑**: Eventuate는 이벤트 타입을 메시지 헤더의 type 이름으로 라우팅 — C의 핸들러가 같은 type 이름을 구독해야 한다(아래 §주의).
- Produces: `ledger_entries`(id,order_id,user_id,amount,created_at) insert(H5@C, Tram 컨슈머 스레드).

- [ ] **Step 1: Application + 구독 설정 + 이벤트(복제)**

`LedgerApplication.java`(@EnableEventHandlers? — Eventuate는 `@DomainEventHandlers` 빈 + 구독 config로 동작):
```java
package sample.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LedgerApplication {
    public static void main(String[] args) { SpringApplication.run(LedgerApplication.class, args); }
}
```

`OrderReserved.java`(B와 동일 필드 + **B와 동일 FQCN `sample.reservation.OrderReserved` 필수** — @EventType 미존재(0.35.0) 대체책):
> **파일 위치 주의**: 이 클래스는 ledger 서비스의 소스 트리 안에 두되 **package 는 `sample.reservation`** 으로 선언한다 →
> 파일 경로 `samples/legacy-tram/ledger/src/main/java/sample/reservation/OrderReserved.java`. 그래야 C의 FQCN 이
> B가 발행한 event-type 헤더(FQCN)와 동일해져 `DefaultDomainEventNameMapping`(FQCN 기반)이 자동 라우팅한다.
```java
package sample.reservation;   // ★ ledger 서비스지만 B와 FQCN 일치를 위해 sample.reservation 패키지로 선언

import io.eventuate.tram.events.common.DomainEvent;

// @EventType 는 0.35.0 에 없음(Task 4 검증). 라우팅 일치는 B와 동일 FQCN으로 달성(복제 유지).
public class OrderReserved implements DomainEvent {
    private Long orderId; private String userId; private int amount;
    public OrderReserved() {}
    public Long getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public int getAmount() { return amount; }
}
```
(C의 `OrderEventHandlers`(package `sample.ledger`)는 `import sample.reservation.OrderReserved;` 로 이 클래스를 참조한다.)

`TramSubscriberConfig.java` (구현 정정: `@EnableEventHandlers` 는 0.35.0 에 미존재 — Task 5 jar 검증. 구독은 `OrderEventHandlers` 의 `DomainEventDispatcher` 빈으로 수동 활성화):
```java
package sample.ledger;

import io.eventuate.tram.spring.consumer.jdbc.TramConsumerJdbcAutoConfiguration;
import io.eventuate.tram.spring.events.subscriber.TramEventSubscriberConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

// @EnableEventHandlers does NOT exist in eventuate-tram-spring-events:0.35.0 (jar 검증).
// TramEventSubscriberConfiguration 가 DomainEventDispatcherFactory 를 제공 → 구독은 DomainEventDispatcher 빈으로 활성화.
@Configuration
// EventuateTramKafkaMessageConsumerConfiguration(io.eventuate.tram.spring.consumer.kafka) = 실제 Kafka MessageConsumer 빈
// (TramConsumerJdbcAutoConfiguration 는 중복검출만 제공 — 미import 시 C가 구독 못함, Task 6 부팅 검증으로 발견).
@Import({EventuateTramKafkaMessageConsumerConfiguration.class, TramConsumerJdbcAutoConfiguration.class, TramEventSubscriberConfiguration.class})
public class TramSubscriberConfig {}
```

> **§주의 (type 매핑 rationale — 구현 정정)**: Eventuate는 발행 시 이벤트의 클래스명(FQCN)을 메시지 `event-type` 헤더로 쓴다(`DefaultDomainEventNameMapping`). 원안은 `@EventType("OrderReserved")` 로 짧은 type 이름을 고정해 B/C의 FQCN 차이를 가교하려 했으나, **Task 4에서 `io.eventuate.tram.events.common.EventType` 가 핀 버전 `eventuate-tram-events:0.35.0.RELEASE` 에 존재하지 않음을 jar로 검증**(해당 jar에는 DomainEvent/DomainEventNameMapping/DefaultDomainEventNameMapping 만 존재). 따라서 B는 FQCN `sample.reservation.OrderReserved` 를 헤더로 발행한다. **정정책: C가 자신의 `OrderReserved` 를 동일 FQCN(`package sample.reservation`)으로 복제**해 FQCN 기반 기본 매핑이 양쪽에서 일치하게 한다(커스텀 `DomainEventNameMapping` 빈 불필요, B 재작업 불필요). 확인 포인트: C의 `OrderReserved` 패키지가 `sample.reservation` 인지, 그리고 publish/subscribe 의 aggregateType 이 둘 다 `"Order"` 인지.

- [ ] **Step 2: Entity + Repository + EventHandlers (ledger_entries insert)**

`LedgerEntry.java`:
```java
package sample.ledger;

import javax.persistence.*;

@Entity @Table(name = "ledger_entries",
        uniqueConstraints = @UniqueConstraint(columnNames = "order_id"))
public class LedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "order_id") private Long orderId;
    @Column(name = "user_id") private String userId;
    @Column(name = "amount") private int amount;
    @Column(name = "created_at") private Long createdAt = System.currentTimeMillis();
    protected LedgerEntry() {}
    public LedgerEntry(Long orderId, String userId, int amount) {
        this.orderId = orderId; this.userId = userId; this.amount = amount;
    }
}
```

`LedgerEntryRepository.java`:
```java
package sample.ledger;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {}
```

`OrderEventHandlers.java`(ledger_entries insert. Eventuate가 received_messages로 멱등 처리).
**구현 정정(0.35.0)**: `@EnableEventHandlers` 부재이므로 구독은 `DomainEventDispatcher` 빈으로 활성화한다 — `TramEventSubscriberConfiguration` 이 제공하는 `DomainEventDispatcherFactory` 를 주입받아 `factory.make("<subscriberId>", handlers)` + `dispatcher.initialize()` 로 등록(없으면 C가 이벤트를 받지 못한다). `OrderReserved` 는 `sample.reservation` 패키지에서 import:
```java
package sample.ledger;

import io.eventuate.tram.events.subscriber.DomainEventDispatcher;
import io.eventuate.tram.events.subscriber.DomainEventDispatcherFactory;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import io.eventuate.tram.events.subscriber.DomainEventHandlers;
import io.eventuate.tram.events.subscriber.DomainEventHandlersBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sample.reservation.OrderReserved;   // ★ B와 동일 FQCN

@Component
public class OrderEventHandlers {
    private final LedgerEntryRepository ledger;
    public OrderEventHandlers(LedgerEntryRepository ledger) { this.ledger = ledger; }

    @Bean   // 구독 활성화 필수 — DomainEventDispatcher 가 핸들러를 Tram 컨슈머에 등록·initialize
    public DomainEventDispatcher domainEventDispatcher(DomainEventDispatcherFactory factory) {
        DomainEventDispatcher dispatcher = factory.make("ledgerServiceEvents", domainEventHandlers());
        dispatcher.initialize();
        return dispatcher;
    }

    public DomainEventHandlers domainEventHandlers() {
        return DomainEventHandlersBuilder
                .forAggregateType("Order")
                .onEvent(OrderReserved.class, this::handle)
                .build();
    }

    @Transactional
    public void handle(DomainEventEnvelope<OrderReserved> env) {
        OrderReserved e = env.getEvent();
        ledger.save(new LedgerEntry(e.getOrderId(), e.getUserId(), e.getAmount()));  // 타깃 비동기 H5 SQL @C
    }
}
```

- [ ] **Step 3: received_messages JPA 폴백 + B3 수신 인터셉터**

`EventuateReceivedMessagesEntity.java`(폴백 — 핀 버전 스키마와 일치, Task 6 init.sql과 동일):
```java
package sample.ledger;

import javax.persistence.*;

/** Eventuate Tram 'received_messages'(중복제거) 테이블 JPA 미러(폴백). 핀 버전 스키마와 정확히 일치할 것. */
@Entity @Table(name = "received_messages")
@IdClass(EventuateReceivedMessagesEntity.PK.class)
public class EventuateReceivedMessagesEntity {
    @Id @Column(name = "consumer_id", length = 255) private String consumerId;   // init.sql과 동일(InnoDB 한계)
    @Id @Column(name = "message_id", length = 255) private String messageId;
    @Column(name = "creation_time", columnDefinition = "BIGINT") private Long creationTime;
    @Column(name = "published", columnDefinition = "SMALLINT") private Short published;
    protected EventuateReceivedMessagesEntity() {}
    public static class PK implements java.io.Serializable {
        private String consumerId; private String messageId;
        public PK() {} public PK(String c, String m) { consumerId = c; messageId = m; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK)) return false; PK p = (PK) o;
            return java.util.Objects.equals(consumerId, p.consumerId)
                    && java.util.Objects.equals(messageId, p.messageId);
        }
        @Override public int hashCode() { return java.util.Objects.hash(consumerId, messageId); }
    }
}
```

`B3MessageInterceptor.java`(폴백 수신측 — `preHandle`에서 B3 복원):
```java
package sample.ledger;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 폴백 수신측: 메시지 B3 헤더 → Brave 컨텍스트 복원(128-bit 보존) → 컨슈머 스레드 MDC에 traceId 반영. */
@Component
@ConditionalOnProperty(name = "eventuate.b3.fallback", havingValue = "true")
public class B3MessageInterceptor implements MessageInterceptor {
    private final Tracing tracing;
    private final ThreadLocal<Tracer.SpanInScope> scope = new ThreadLocal<>();
    public B3MessageInterceptor(Tracing tracing) { this.tracing = tracing; }

    @Override public void preHandle(String subscriberId, Message message) {
        String traceId = message.getHeader("X-B3-TraceId").orElse(null);
        String spanId = message.getHeader("X-B3-SpanId").orElse(null);
        if (traceId == null || spanId == null) return;
        // 128-bit traceId 보존(리뷰 GPT I7): 32-hex면 상위 64-bit를 traceIdHigh로.
        String hex = traceId.length() == 32 ? traceId : ("0000000000000000" + traceId);
        long high = Long.parseUnsignedLong(hex.substring(0, 16), 16);
        long low = Long.parseUnsignedLong(hex.substring(16), 16);
        brave.propagation.TraceContext ctx = brave.propagation.TraceContext.newBuilder()
                .traceIdHigh(high).traceId(low)
                .spanId(Long.parseUnsignedLong(spanId, 16))
                .sampled(true).build();
        Span span = tracing.tracer().toSpan(ctx);
        scope.set(tracing.tracer().withSpanInScope(span));
    }

    @Override public void postHandle(String subscriberId, Message message, Throwable t) {
        Tracer.SpanInScope s = scope.get();
        if (s != null) { s.close(); scope.remove(); }
    }
}
```
> **주의**: 128-bit를 보존해 C 로그의 traceId가 주입한 full 32-hex와 동일하게 찍히도록 한다(폴백 시에도 R1 grep 일치). 런북 grep은 full/우측16hex 둘 다 허용(이중 안전). 1순위 sleuth-integration이 동작하면 이 폴백(`eventuate.b3.fallback=false` 기본) 비활성.

- [ ] **Step 4: application.yml + logback + Dockerfile + 빌드 확인**

`application.yml`(C는 `ledgerdb`):
```yaml
server: { port: 8080 }
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://mysql:3306/ledgerdb}
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:rootpw}
  jpa:
    hibernate: { ddl-auto: ${SPRING_JPA_HIBERNATE_DDL_AUTO:update} }
eventuate:
  b3: { fallback: ${EVENTUATE_B3_FALLBACK:false} }
management: { endpoints: { web: { exposure: { include: health } } } }
```
(logback/Dockerfile = Task 3 동일.)

Run: `cd samples/legacy-tram/ledger && gradle bootJar -q && ls build/libs/*.jar`
Expected: jar 생성.

- [ ] **Step 5: Commit**

```bash
git add samples/legacy-tram/ledger
git commit -m "feat(sample): ledger (C) — Tram subscriber inserts ledger_entries (target async H5 SQL)"
```

---

## Task 6: 인프라 compose (kafka + mysql binlog + eventuate-cdc-service + 3앱) + init.sql

**Files:**
- Create: `samples/legacy-tram/docker-compose.yml`, `samples/legacy-tram/docker-compose.e2e.yml`(호스트 포트 publish), `samples/legacy-tram/docker-compose.no-initsql.yml`(폴백 검증), `samples/legacy-tram/mysql/init.sql`, `samples/legacy-tram/mysql/my.cnf`, `samples/legacy-tram/README.md`

**Interfaces:**
- Produces: `samples/legacy-tram/docker-compose.yml` — 빌더 attach가 `--app-service order-web --capture-services order-web,reservation,ledger` 로 무는 자급 스택. 서비스명 `order-web`/`reservation`/`ledger`/`mysql`/`kafka`/`eventuate-cdc-service`.

- [ ] **Step 1: mysql binlog 설정 + Eventuate 스키마 init.sql**

`mysql/my.cnf`:
```ini
[mysqld]
server-id=1
log-bin=mysql-bin
binlog-format=ROW
```

`mysql/init.sql`. **정본 = Task 2 핀 버전의 Eventuate Tram + CDC 공식 MySQL 스키마**(eventuate-tram-core의 `eventuate-tram-embedded-schema`/`mysql.sql` + eventuate-cdc의 `cdc_monitoring`/`offset_store`). 아래는 InnoDB/utf8mb4 한계·CDC 메타 테이블·builder/CDC 자격증명을 반영한 **검증된 출발점**(리뷰 반영) — Task 2에서 공식 스키마와 대조해 컬럼/타입을 최종 확정하고 폴백 JPA 엔티티(Task 4/5)와 **완전히 일치**시킨다:
```sql
CREATE DATABASE IF NOT EXISTS orderdb;
CREATE DATABASE IF NOT EXISTS reservationdb;
CREATE DATABASE IF NOT EXISTS ledgerdb;

-- B(reservationdb): Eventuate 아웃박스 + CDC 메타 테이블(offset_store/cdc_monitoring) — 리뷰 Gemini I5
USE reservationdb;
CREATE TABLE IF NOT EXISTS message (
  id VARCHAR(255) PRIMARY KEY,                 -- 리뷰 Gemini I7: InnoDB 3072B 한계 → 255
  destination VARCHAR(1000) NOT NULL,
  headers LONGTEXT NOT NULL,                   -- 폴백 엔티티와 동일(리뷰 Sonnet I8/GPT I5)
  payload LONGTEXT NOT NULL,
  published SMALLINT DEFAULT 0,
  message_partition SMALLINT,                  -- 폴백 엔티티에도 포함(리뷰 Gemini I1)
  creation_time BIGINT
);
CREATE TABLE IF NOT EXISTS cdc_monitoring (
  reader_id VARCHAR(255) PRIMARY KEY,
  last_time BIGINT
);
CREATE TABLE IF NOT EXISTS offset_store (
  client_name VARCHAR(255) PRIMARY KEY,
  serialized_offset VARCHAR(255)
);
-- C(ledgerdb): 중복제거
USE ledgerdb;
CREATE TABLE IF NOT EXISTS received_messages (
  consumer_id VARCHAR(255),                    -- 복합 PK도 255로(InnoDB 한계)
  message_id VARCHAR(255),
  published SMALLINT DEFAULT 0,
  creation_time BIGINT,
  PRIMARY KEY(consumer_id, message_id)
);

-- builder attach가 ComposeInspector로 읽는 앱 유저(MYSQL_USER/PASSWORD) — 3 DB 접근 권한
CREATE USER IF NOT EXISTS 'app'@'%' IDENTIFIED WITH mysql_native_password BY 'apppw';
GRANT ALL PRIVILEGES ON orderdb.* TO 'app'@'%';
GRANT ALL PRIVILEGES ON reservationdb.* TO 'app'@'%';
GRANT ALL PRIVILEGES ON ledgerdb.* TO 'app'@'%';
-- CDC 유저(binlog 읽기). caching_sha2 미지원 클라이언트 대비 native_password(리뷰 Gemini I8)
CREATE USER IF NOT EXISTS 'cdc'@'%' IDENTIFIED WITH mysql_native_password BY 'cdcpw';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'cdc'@'%';
GRANT ALL PRIVILEGES ON reservationdb.* TO 'cdc'@'%';
FLUSH PRIVILEGES;
```
> **주의**: 위 컬럼/타입은 InnoDB·CDC 제약을 반영한 출발점이다. **Task 2 핀 버전의 Eventuate 공식 스키마**(message/received_messages/cdc_monitoring/offset_store)와 대조해 최종 확정하고, 폴백 JPA 엔티티(Task 4/5)와 컬럼/타입을 **완전히 동일**하게 맞춘다.

- [ ] **Step 2: docker-compose.yml**

`docker-compose.yml`(KRaft Kafka[zookeeper 제거, 리뷰 Sonnet I9] + 호스트 리스너[리뷰 Sonnet I2/Gemini I4] + DB 앱/CDC 자격증명[리뷰 GPT I4] + CDC binlog unique id[리뷰 Sonnet I6] + CDC를 up 그래프에 포함):
```yaml
services:
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    environment:
      KAFKA_NODE_ID: "1"
      KAFKA_PROCESS_ROLES: "broker,controller"
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:29093"
      KAFKA_LISTENERS: "INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:29093,EXTERNAL://0.0.0.0:59092"
      KAFKA_ADVERTISED_LISTENERS: "INTERNAL://kafka:9092,EXTERNAL://localhost:59092"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT"
      KAFKA_INTER_BROKER_LISTENER_NAME: "INTERNAL"
      KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1"
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"
    ports: ["59092:59092"]                       # 호스트(빌더)가 localhost:59092로 도달
    healthcheck:
      test: ["CMD","kafka-broker-api-versions","--bootstrap-server","localhost:9092"]
      interval: 10s
      timeout: 5s
      retries: 12
  mysql:
    image: mysql:8.0
    command: ["--server-id=1","--log-bin=mysql-bin","--binlog-format=ROW"]
    environment:
      MYSQL_ROOT_PASSWORD: rootpw
      MYSQL_DATABASE: orderdb            # ComposeInspector가 읽는 키(builder DB 자격증명)
      MYSQL_USER: app
      MYSQL_PASSWORD: apppw
    volumes:
      - ./mysql/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    healthcheck:
      test: ["CMD","mysqladmin","ping","-h","localhost","-prootpw"]
      interval: 10s
      timeout: 5s
      retries: 12
  eventuate-cdc-service:
    image: eventuateio/eventuate-cdc-service:0.16.0.RELEASE   # Task 2 확정 태그
    depends_on:
      mysql: { condition: service_healthy }
      kafka: { condition: service_healthy }
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/reservationdb
      SPRING_DATASOURCE_USERNAME: cdc
      SPRING_DATASOURCE_PASSWORD: cdcpw
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: com.mysql.cj.jdbc.Driver
      EVENTUATELOCAL_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      EVENTUATELOCAL_CDC_DB_USER_NAME: cdc
      EVENTUATELOCAL_CDC_DB_PASSWORD: cdcpw
      EVENTUATELOCAL_CDC_READER_NAME: MySqlReader
      EVENTUATELOCAL_CDC_MYSQL_BINLOG_CLIENT_UNIQUE_ID: "1234"   # 리뷰 Sonnet I6
      EVENTUATE_CDC_TYPE: EventuateTram
  order-web:
    build: ./order-web
    depends_on:
      mysql: { condition: service_healthy }
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/orderdb
      SPRING_DATASOURCE_USERNAME: app
      SPRING_DATASOURCE_PASSWORD: apppw
      RESERVATION_URL: http://reservation:8080
      LOGGING_LEVEL_ORG_HIBERNATE_SQL: DEBUG                                  # 리뷰 GPT I3
      LOGGING_LEVEL_ORG_HIBERNATE_TYPE_DESCRIPTOR_SQL_BASICBINDER: TRACE      # H5 bind
    # app/jacoco 포트는 빌더 attach override가 publish; 직접검증은 docker-compose.e2e.yml
  reservation:
    build: ./reservation
    depends_on:
      mysql: { condition: service_healthy }
      kafka: { condition: service_healthy }
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/reservationdb
      SPRING_DATASOURCE_USERNAME: app
      SPRING_DATASOURCE_PASSWORD: apppw
      EVENTUATELOCAL_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      LOGGING_LEVEL_ORG_HIBERNATE_SQL: DEBUG
      LOGGING_LEVEL_ORG_HIBERNATE_TYPE_DESCRIPTOR_SQL_BASICBINDER: TRACE
  ledger:
    build: ./ledger
    depends_on:
      eventuate-cdc-service: { condition: service_started }
      mysql: { condition: service_healthy }
      kafka: { condition: service_healthy }
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/ledgerdb
      SPRING_DATASOURCE_USERNAME: app
      SPRING_DATASOURCE_PASSWORD: apppw
      EVENTUATELOCAL_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      LOGGING_LEVEL_ORG_HIBERNATE_SQL: DEBUG
      LOGGING_LEVEL_ORG_HIBERNATE_TYPE_DESCRIPTOR_SQL_BASICBINDER: TRACE
```
> `ledger.depends_on: eventuate-cdc-service` 가 빌더 `up --wait order-web reservation ledger` 시 CDC를 끌어올리는 핵심 연결(스펙 §5). SQL 로깅을 개별 env로 켜 **직접 R1 검증(빌더 override 없이)에서도 C 로그에 H5 SQL이 출력**되게 한다(리뷰 GPT I3) — `SPRING_APPLICATION_JSON` 비의존 제약 준수.

`docker-compose.e2e.yml`(직접 R1 검증/빌더 attach가 호스트에서 도달할 포트 publish — 리뷰 Sonnet I10/Gemini I3/GPT I2):
```yaml
services:
  order-web: { ports: ["58080:8080"] }
  mysql: { ports: ["53306:3306"] }
  # kafka 59092는 base compose에서 이미 publish
```

`docker-compose.no-initsql.yml`(폴백 경로 검증용 — init.sql 볼륨 제거, 리뷰 Sonnet I7):
```yaml
services:
  mysql: { volumes: [] }
```

- [ ] **Step 3: 스택 부팅 + 스키마 부트스트랩 smoke (필수, 두 경로)**

Run(전체 스택 up 후 검증):
```bash
cd samples/legacy-tram
docker compose up -d --build --wait order-web reservation ledger eventuate-cdc-service
# 비즈니스 + Eventuate 테이블 존재 확인(init.sql 경로)
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN reservationdb;" | grep -i message
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN ledgerdb;" | grep -i received_messages
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN orderdb;" | grep -i orders
docker compose down -v
```
Expected: `message`, `received_messages`, `orders` 모두 존재.

폴백 경로(init.sql 비활성 변형) 1회 확인:
```bash
# init.sql 볼륨을 빼고(또는 빈 파일로) ddl-auto=update 폴백만으로 테이블이 생기는지
docker compose -f docker-compose.yml -f docker-compose.no-initsql.yml up -d --build --wait reservation ledger
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN reservationdb;" | grep -i message
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN ledgerdb;" | grep -i received_messages
docker compose down -v
```
(`docker-compose.no-initsql.yml` = mysql의 init.sql 볼륨 마운트를 제거하는 override를 생성.)
Expected: 폴백(JPA `ddl-auto=update`)만으로도 `message`/`received_messages` 생성.

- [ ] **Step 4: README + Commit**

`README.md`: 스택 구성도, 사전조건(Docker, 빌더 PR #60 + Task 1 SchemaExtractor), 부팅·E2E 실행법, 폴백 토글(`EVENTUATE_B3_FALLBACK=true`) 설명.

```bash
git add samples/legacy-tram/docker-compose.yml samples/legacy-tram/docker-compose.e2e.yml \
        samples/legacy-tram/docker-compose.no-initsql.yml samples/legacy-tram/mysql \
        samples/legacy-tram/README.md
git commit -m "feat(sample): docker-compose (kraft-kafka+mysql-binlog+cdc+3 apps) + Eventuate init.sql + schema smoke"
```

---

## Task 7: E2E 런북 (빌더 fail-fast → up → R1·캡처·노이즈배제 → down)

**Files:**
- Create: `e2e/run-legacy-tram-sleuth-e2e.sh`, `e2e/request-legacy-orders.json`

**Interfaces:**
- Consumes: Task 1~6 산출물 + PR #60 빌더(`--trace-mode sleuth`/`--capture-services`).
- Produces: 수용 게이트 스크립트. exit 0 = 3종 PASS.

- [ ] **Step 1: 런북 스크립트**

`e2e/run-legacy-tram-sleuth-e2e.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STACK="$ROOT/samples/legacy-tram"
OUT="$(mktemp -d)"
APP_PORT=58080; JACOCO_PORT=56300; DB_PORT=53306

# (0) 빌더 sleuth 지원 fail-fast 점검 (스펙 §2)
if ! graph-rag-builder build --help 2>&1 | grep -q -- '--trace-mode'; then
  echo "FAIL: builder lacks --trace-mode (need PR #60 merged/built)"; exit 2
fi
if ! graph-rag-builder build --help 2>&1 | grep -q -- '--capture-services'; then
  echo "FAIL: builder lacks --capture-services (need PR #60)"; exit 2
fi

# base + e2e override(호스트 포트 publish)를 항상 함께 사용
DC="docker compose -f docker-compose.yml -f docker-compose.e2e.yml"
cleanup() { (cd "$STACK" && $DC down -v) || true; }
trap cleanup EXIT

# (1) 스택 up (CDC는 ledger depends_on으로 포함; e2e override가 order-web:58080/mysql:53306 publish)
(cd "$STACK" && $DC up -d --build --wait order-web reservation ledger eventuate-cdc-service)

# (1b) R1 독립 검증: 알려진 B3로 직접 curl + C 로그 폴링 (요청 직전 시각 앵커 → --since로 신규 라인만)
TRACE="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; SPAN="bbbbbbbbbbbbbbbb"
SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"            # 리뷰 Sonnet I4: pre-request 앵커(재실행 false-positive 방지)
curl -fsS -X POST "http://localhost:$APP_PORT/orders" \
  -H "Content-Type: application/json" \
  -H "X-B3-TraceId: $TRACE" -H "X-B3-SpanId: $SPAN" -H "X-B3-Sampled: 1" \
  -d '{"userId":"u1","amount":100}' >/dev/null
echo "[R1] waiting for C(ledger) to log SQL with trace $TRACE ..."
R1=FAIL
for i in $(seq 1 120); do   # 120 * 250ms = 30s
  # full 32-hex 또는 우측 16-hex 둘 다 허용(폴백이 64-bit만 복원할 수 있음 — 리뷰 GPT I7)
  if (cd "$STACK" && $DC logs --since "$SINCE" ledger 2>/dev/null) \
        | grep -i 'org.hibernate.SQL' \
        | grep -iqE "${TRACE}|${TRACE: -16}"; then R1=PASS; break; fi
  sleep 0.25
done
echo "[R1] $R1"
if [ "$R1" != PASS ]; then
  echo "=== R1 FAIL diag dump ==="
  (cd "$STACK" && $DC logs --tail=200 order-web reservation ledger eventuate-cdc-service kafka)
  echo "힌트: 1순위 sleuth-integration 미동작이면 EVENTUATE_B3_FALLBACK=true로 폴백 인터셉터 켜고 재시도."
  exit 1
fi

# (2) builder attach 캡처 (sleuth, 멀티서비스)
graph-rag-builder build --attach \
  --sut-compose "$STACK/docker-compose.yml" \
  --app-service order-web --app-container-port 8080 --app-port "$APP_PORT" --jacoco-port "$JACOCO_PORT" \
  --jdbc-url "jdbc:mysql://localhost:$DB_PORT/orderdb" --kafka-bootstrap localhost:59092 \
  --trace-mode sleuth --capture-services order-web,reservation,ledger \
  --sut-src "$STACK/order-web/src/main/java" --sut-jar "$STACK/order-web/build/libs/order-web.jar" \
  --out "$OUT"

# (2b) graph에 A/B/C SQL 귀속 확인 (최소 집합)
GRAPH="$OUT/graph.json"
CAP2=PASS
for needle in 'insert into orders' 'insert into reservations' 'insert into ledger_entries'; do
  grep -iq "$needle" "$GRAPH" || { echo "[CAP] missing: $needle"; CAP2=FAIL; }
done
echo "[CAP] $CAP2"

# (3) 인프라 노이즈 배제: received_messages 폴링 등 백그라운드 SQL 미포함
NOISE=PASS
if grep -iq 'from received_messages' "$GRAPH" || grep -iq 'from message where' "$GRAPH"; then
  echo "[NOISE] background CDC SQL leaked into graph"; NOISE=FAIL; fi
echo "[NOISE] $NOISE"

[ "$R1" = PASS ] && [ "$CAP2" = PASS ] && [ "$NOISE" = PASS ] && { echo "E2E PASS"; exit 0; }
echo "E2E FAIL"; exit 1
```
> R1 독립 curl과 빌더 attach가 같은 app 포트를 쓰므로, 런북은 직접검증용 포트 publish override(`docker-compose.e2e.yml`)를 추가하거나, R1 검증을 빌더 실행 로그 기반으로 합친다. 구현 시 포트 충돌 없게 조정(직접검증 → down → 빌더 attach 순서로 분리해도 됨).

- [ ] **Step 2: 런북 실행 (수용 게이트)**

Run: `bash e2e/run-legacy-tram-sleuth-e2e.sh; echo "exit=$?"`
Expected: `E2E PASS`, exit 0 (R1/CAP/NOISE 전부 PASS). **R1 FAIL이면** = Eventuate↔Sleuth 전파 미동작 → `EVENTUATE_B3_FALLBACK=true`로 폴백 인터셉터 켜고 재시도; 그래도 FAIL이면 R1=거짓 판정(스펙 §10) 문서화.

- [ ] **Step 3: Commit**

```bash
git add e2e/run-legacy-tram-sleuth-e2e.sh e2e/request-legacy-orders.json
git commit -m "test(e2e): legacy-tram sleuth acceptance runbook (R1 + capture + noise exclusion)"
```

---

## Task 8: 수용 + 자기검토 (DoD)

**Files:** 없음(검증/문서 마무리).

- [ ] **Step 1: 빌더 보정 단위테스트 green**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.schema.SchemaExtractor*Test' --console=plain`
Expected: PASS (MySQL + Postgres).

- [ ] **Step 2: 스키마 부트스트랩 smoke 두 경로 green** (Task 6 Step 3 재확인.)

- [ ] **Step 3: E2E 런북 PASS** (Task 7 Step 2.) R1 결과(PASS 또는 R1=거짓 판정)를 `samples/legacy-tram/README.md`에 기록.

- [ ] **Step 4: DoD 자기검토**
- §8 수용 3종(R1·캡처·노이즈배제) 결과 기록 ✔
- 스키마 이중경로(init.sql + JPA 폴백) 검증 ✔
- 빌더 SchemaExtractor MySQL 보정 ✔
- 미달 시 해당 Task로 회귀.

- [ ] **Step 5: 문서/마무리** — README 최신화, R1 진위 기록. (PR은 사용자 GO 후; PR #60 머지 + 빌더 보정 반영이 선행.)

---

## Self-Review (작성자 점검)

1. **Spec coverage**: §2 빌더 의존성/MySQL→T1·T7(fail-fast); §3 도메인→T3/T4/T5(202·각 홉 SQL); §4 데이터/이중스키마→T4/T5(폴백 엔티티)·T6(init.sql)·T6 smoke; §5 컴포넌트/CDC depends_on→T6; §6 trace 전파(1순위+폴백)→T4/T5(sleuth-integration·B3MessageInterceptor); §7 attach 호출→T7; §8 수용 3종→T7; §9 테스트→T1/T6/T7/T8; §10 리스크(R1 거짓 판정·버전)→T2(핀)·T7(폴백 토글). 
2. **Placeholder scan**: 버전 핀과 Eventuate 스키마는 T2에서 "해소되는 실제 조합/공식 스키마 정본 대조"로 확정하는 **실행 가능한 절차**로 명시(추측 금지). 폴백 엔티티/ init.sql은 핀 버전 공식 스키마와 일치시키라는 구체 지시 + 대조 대상 명시. TBD 없음.
3. **Type consistency**: `OrderReserved`(B/C 모두 FQCN `sample.reservation.OrderReserved` 로 통일 → 기본 FQCN 매핑 라우팅 일치; @EventType 는 0.35.0 미존재로 폐기, Task 4 검증)·`DomainEventPublisher.publish("Order", id, [event])`↔`DomainEventHandlersBuilder.forAggregateType("Order").onEvent(OrderReserved.class,...)`; 서비스명(order-web/reservation/ledger)·DB(orderdb/reservationdb/ledgerdb)·env 키 일관; 빌더 `extract(Connection)` 시그니처 불변(T1); 런북 플래그 `--trace-mode sleuth --capture-services`(Spec 2)와 일치.

**알려진 실행 리스크(계획 내 명시)**: Eventuate 버전/스키마 정합성(T2가 정공), R1 미전파 시 폴백→그래도 실패면 R1=거짓 판정(가치 있는 결과).

---

## 3-Model 리뷰 반영 기록 (2026-06-18)

3-model 교차 리뷰(Sonnet + Gemini 3.5 Flash High + GPT-5.5) 판정·반영:

- **수용(critical)** — Java8 `Map.of`(Java9+) → LinkedHashMap/singletonMap(GPT I1); Kafka 호스트 리스너/포트 미publish → KRaft + EXTERNAL listener + `docker-compose.e2e.yml` 포트 publish(Sonnet I2/I10·Gemini I3/I4·GPT I2); builder DB 자격증명(ComposeInspector가 MYSQL_USER/PASSWORD 읽음) → mysql에 app 유저/권한 추가(GPT I4); CDC 메타 테이블 `offset_store`/`cdc_monitoring` 누락 → init.sql 추가(Gemini I5); `@Bean` 누락(`domainEventHandlers`) → 추가(Gemini I6); InnoDB PK VARCHAR(1000)>3072B → 255(Gemini I7); testcontainers junit 확장 의존성 누락 → 추가(Sonnet I1).
- **수용(important)** — JPA 폴백 엔티티 ↔ init.sql 스키마 불일치(headers/payload/message_partition) → 일치화(Sonnet I8·Gemini I1·GPT I5); `eventuate.database.schema: eventuate` 오설정 제거(Sonnet I5·Gemini I2); SQL 로깅 미활성(직접 R1 경로) → 개별 env로 H5 SQL/bind 로깅 on(GPT I3); R1 폴링 pre-request 앵커 없음 → `--since`(Sonnet I4); CDC binlog unique id 추가(Sonnet I6); zookeeper race → KRaft 전환(Sonnet I9); mysql caching_sha2 → native_password(Gemini I8); 폴백 64-bit만 복원 → 128-bit 보존 + R1 grep full/우측16hex 허용(GPT I7); `@EventType` 코드블록 직접 포함(Gemini I9·GPT I8); ddl-auto env 키 underscore 통일(GPT I6); no-initsql/e2e override 파일 선언(Sonnet I7).
- **수용(recommended)** — `@EnableEventHandlers`를 @Configuration으로 이동(Sonnet I11); 멀티스테이지 Dockerfile로 wrapper/사전빌드 불필요(Sonnet I13); unused import 제거(Sonnet I12).
- **반려(1건, 근거)** — Sonnet I3 "connector groupId를 com.mysql:mysql-connector-j로": 그 좌표는 빌더(Java17) 기준이고 **샘플은 Boot 2.7**이라 BOM이 구 좌표 `mysql:mysql-connector-java`(버전 자동관리)를 관리한다. 신 좌표를 버전 없이 쓰면 미해결 → 구 좌표 유지가 정답. (receiving-code-review: 맹목 적용 회피.)
