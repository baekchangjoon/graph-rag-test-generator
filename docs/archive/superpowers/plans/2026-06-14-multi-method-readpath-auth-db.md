# 다중 method · GET read-path 시드/합성 · JWT 인증 · DB 비종속 — 구현 계획 (1단계)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** in-repo `samples/order-service`에 (1) GET/PUT/DELETE 인덱싱, (2) GET 조회 경로 시드+결정적 합성, (3) JWT 인증 주입, (4) compose 기반 DB 비종속을 모두 걸어 e2e 풀사이클을 GREEN으로 만든다.

**Architecture:** 탐색 엔진이 다루는 "입력"을 평탄한 `JsonNode {paramName:value}`로 통일하고, `EndpointInvoker` 구현(`httpInvoker`)이 `Endpoint.params`의 `ParamKind`로 path 치환·query·body를 분배한다. 시드는 builder가 `ReadInputSynthesizer`로 생성해 `RequiredSeed` graph 사실로 기록, generator가 재현한다. 인증은 petclinic `ApiBlackBoxTestSupport` 패턴(login 1회 캐싱 + Bearer)을 builder 탐색·생성 테스트 양쪽에 옮긴다. DB는 SUT compose에서 `DbConfig`로 탐지해 type별 `JdbcDatabaseContainer`를 띄운다.

**Tech Stack:** Java 17, Gradle (Kotlin DSL), Spoon 10.x, Testcontainers(jdbc), SnakeYAML(또는 Jackson YAML), java.net.http, RestAssured, JUnit 5, Spring Boot 3.3.x(SUT), jjwt(SUT).

**Spec:** `docs/superpowers/specs/2026-06-14-multi-method-readpath-auth-db-design.md`

**진행 규약:** 각 태스크 TDD(테스트 먼저 → 최소 구현 → 리팩터). 완료 시 commit + `progress/<step>.md`. 주요 결정은 `docs/decisions/<topic>.md`.

**검증 명령 약어:** `./gradlew :<module>:test --tests <FQN>` 단위, `./gradlew build` 전체.

---

## Phase A — shared-model 확장

### Task A1: `RequiredSeed` + `ExploredPath.requiredSeedIds` + `GraphAsset.seeds`

**Files:**
- Create: `shared-model/src/main/java/io/graphrag/model/RequiredSeed.java`
- Modify: `shared-model/src/main/java/io/graphrag/model/ExploredPath.java`
- Modify: `shared-model/src/main/java/io/graphrag/model/GraphAsset.java`
- Test: `shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java`

- [ ] **Step 1: 라운드트립 테스트에 RequiredSeed/필드 추가 (실패 확인)**

`JsonRoundTripTest.java`에 케이스 추가:

```java
@Test
void requiredSeed_roundTrips() {
    RequiredSeed seed = new RequiredSeed("seed-p1-1", "p1", "orders",
            List.of("id", "user_id"), List.of("1", "probe-userId"));
    RequiredSeed back = Json.mapper().readValue(
            Json.mapper().writeValueAsString(seed), RequiredSeed.class);
    assertThat(back).isEqualTo(seed);
}

@Test
void exploredPath_requiredSeedIds_roundTripsAndDefaultsEmpty() throws Exception {
    ExploredPath path = new ExploredPath("p1", "e1", Json.mapper().createObjectNode(),
            200, Json.mapper().createObjectNode(), List.of(), List.of(), List.of(),
            "heuristic", List.of(), List.of(), List.of("seed-p1-1"));
    ExploredPath back = Json.mapper().readValue(
            Json.mapper().writeValueAsString(path), ExploredPath.class);
    assertThat(back.requiredSeedIds()).containsExactly("seed-p1-1");

    // 구버전 그래프(requiredSeedIds 누락)는 빈 리스트로 정규화
    String legacy = "{\"id\":\"p\",\"endpointId\":\"e\",\"sampleInput\":{},"
            + "\"expectedStatus\":200,\"sampleResponse\":{}}";
    assertThat(Json.mapper().readValue(legacy, ExploredPath.class).requiredSeedIds())
            .isEmpty();
}
```

- [ ] **Step 2: 컴파일/테스트 실패 확인**

Run: `./gradlew :shared-model:test --tests io.graphrag.model.JsonRoundTripTest`
Expected: 컴파일 실패 (`RequiredSeed` 없음, `ExploredPath` 12-인자 생성자 없음).

- [ ] **Step 3: RequiredSeed 생성**

```java
package io.graphrag.model;

import java.util.List;

/** builder가 read-path 탐색을 위해 사전 삽입한 시드 행. generator가 그대로 재현한다. */
public record RequiredSeed(
        String id,
        String pathId,
        String table,
        List<String> columns,
        List<String> values) {
}
```

- [ ] **Step 4: ExploredPath에 requiredSeedIds 추가 (맨 끝 필드 + 후방호환 정규화)**

`ExploredPath`의 마지막 컴포넌트로 `List<String> requiredSeedIds` 추가, compact 생성자에 정규화 한 줄 추가:

```java
        List<String> constraints,
        List<String> validationWarnings,
        List<String> requiredSeedIds) {

    public ExploredPath {
        capturedHttpCallIds = capturedHttpCallIds == null ? List.of() : capturedHttpCallIds;
        branchesTaken = branchesTaken == null ? List.of() : branchesTaken;
        discoveredBy = discoveredBy == null ? "unknown" : discoveredBy;
        constraints = constraints == null ? List.of() : constraints;
        validationWarnings = validationWarnings == null ? List.of() : validationWarnings;
        requiredSeedIds = requiredSeedIds == null ? List.of() : requiredSeedIds;
    }
```

- [ ] **Step 5: GraphAsset에 seeds 추가 (맨 끝 + 후방호환)**

`GraphAsset`의 마지막 컴포넌트로 `List<RequiredSeed> seeds` 추가, compact 생성자에 `seeds = seeds == null ? List.of() : seeds;` 추가.

- [ ] **Step 6: 컴파일 에러 추적 — 기존 호출부 보정**

`EndpointExplorationRunner.java:111` 의 `new ExploredPath(...)`에 마지막 인자가 곧 추가되므로 이 시점에선 임시로 `List.of()`를 추가해 컴파일만 통과시킨다(Task E2에서 실제 값으로 교체). `GraphAsset`을 조립하는 곳(BuilderCli)에도 마지막 인자 `List.of()` 추가(Task E3에서 교체).

Run: `./gradlew :shared-model:test --tests io.graphrag.model.JsonRoundTripTest`
Expected: PASS

- [ ] **Step 7: 전체 컴파일 확인 + 커밋**

```bash
./gradlew compileJava compileTestJava
git add shared-model graph-rag-builder test-generator
git commit -m "feat(shared-model): RequiredSeed + ExploredPath.requiredSeedIds + GraphAsset.seeds"
```

---

## Phase B — DB 비종속 (compose에서 DB 결정)

### Task B1: `DbConfig` + `ComposeInspector`

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/DbConfig.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/ComposeInspector.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/ComposeInspectorTest.java`
- Modify: `graph-rag-builder/build.gradle.kts` (YAML 파서 의존)

- [ ] **Step 1: 의존 추가**

`graph-rag-builder/build.gradle.kts`의 dependencies에:

```kotlin
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
```

- [ ] **Step 2: 실패 테스트 작성**

```java
package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ComposeInspectorTest {

    @Test
    void detectsPostgresServiceAndCredentials(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path compose = dir.resolve("docker-compose.yml");
        Files.writeString(compose, """
                services:
                  db:
                    image: postgres:15
                    environment:
                      POSTGRES_DB: petclinic
                      POSTGRES_USER: petclinic
                      POSTGRES_PASSWORD: secret
                  app:
                    image: app:latest
                """);
        DbConfig config = ComposeInspector.detectDb(compose);
        assertThat(config.type()).isEqualTo(DbConfig.Type.POSTGRES);
        assertThat(config.image()).isEqualTo("postgres:15");
        assertThat(config.dbName()).isEqualTo("petclinic");
        assertThat(config.user()).isEqualTo("petclinic");
        assertThat(config.password()).isEqualTo("secret");
    }

    @Test
    void detectsMysqlService(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path compose = dir.resolve("docker-compose.yml");
        Files.writeString(compose, """
                services:
                  mysql:
                    image: mysql:8.4
                    environment:
                      MYSQL_DATABASE: petclinic
                      MYSQL_USER: petclinic
                      MYSQL_PASSWORD: secret
                """);
        DbConfig config = ComposeInspector.detectDb(compose);
        assertThat(config.type()).isEqualTo(DbConfig.Type.MYSQL);
        assertThat(config.dbName()).isEqualTo("petclinic");
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.env.ComposeInspectorTest`
Expected: 컴파일 실패 (`DbConfig`, `ComposeInspector` 없음).

- [ ] **Step 4: DbConfig 작성**

```java
package io.graphrag.builder.env;

/** SUT의 docker-compose에서 추출한 DB 구성. */
public record DbConfig(Type type, String image, String dbName, String user, String password) {

    public enum Type { POSTGRES, MYSQL, MARIADB }
}
```

- [ ] **Step 5: ComposeInspector 작성**

```java
package io.graphrag.builder.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/** docker-compose에서 DB 서비스(postgres/mysql/mariadb)를 찾아 DbConfig로 추출한다. */
public final class ComposeInspector {

    private static final YAMLMapper YAML = new YAMLMapper();

    private ComposeInspector() {
    }

    public static DbConfig detectDb(Path composePath) {
        try {
            JsonNode root = YAML.readTree(composePath.toFile());
            JsonNode services = root.path("services");
            Iterator<Map.Entry<String, JsonNode>> it = services.fields();
            while (it.hasNext()) {
                JsonNode service = it.next().getValue();
                String image = service.path("image").asText("");
                DbConfig.Type type = typeForImage(image);
                if (type != null) {
                    JsonNode env = service.path("environment");
                    return new DbConfig(type, image,
                            envValue(env, type, "DB"),
                            envValue(env, type, "USER"),
                            envValue(env, type, "PASSWORD"));
                }
            }
            throw new IllegalStateException("no DB service (postgres/mysql/mariadb) in " + composePath);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static DbConfig.Type typeForImage(String image) {
        String lower = image.toLowerCase();
        if (lower.contains("postgres")) return DbConfig.Type.POSTGRES;
        if (lower.contains("mariadb")) return DbConfig.Type.MARIADB;
        if (lower.contains("mysql")) return DbConfig.Type.MYSQL;
        return null;
    }

    /** POSTGRES_DB / MYSQL_DATABASE 등 키 차이를 흡수. environment는 map 또는 list 형식. */
    private static String envValue(JsonNode env, DbConfig.Type type, String suffix) {
        String key = switch (type) {
            case POSTGRES -> "POSTGRES_" + suffix;
            case MYSQL, MARIADB -> "MYSQL_" + (suffix.equals("DB") ? "DATABASE" : suffix);
        };
        if (env.isObject()) {
            return env.path(key).asText("");
        }
        if (env.isArray()) {   // ["KEY=value", ...] 형식
            for (JsonNode entry : env) {
                String text = entry.asText("");
                if (text.startsWith(key + "=")) {
                    return text.substring(key.length() + 1);
                }
            }
        }
        return "";
    }
}
```

- [ ] **Step 6: PASS 확인 + 커밋**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.env.ComposeInspectorTest`
Expected: PASS

```bash
git add graph-rag-builder
git commit -m "feat(builder): ComposeInspector — detect DB type/creds from SUT compose"
```

### Task B2: `SqlDialect` — dialect별 멱등 INSERT

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/SqlDialect.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/SqlDialectTest.java`

- [ ] **Step 1: 실패 테스트**

```java
package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SqlDialectTest {

    @Test
    void postgresUsesOnConflict() {
        String sql = SqlDialect.idempotentInsert(DbConfig.Type.POSTGRES,
                "orders", java.util.List.of("id", "user_id"));
        assertThat(sql).isEqualTo(
                "INSERT INTO orders (id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING");
    }

    @Test
    void mysqlUsesInsertIgnore() {
        String sql = SqlDialect.idempotentInsert(DbConfig.Type.MYSQL,
                "orders", java.util.List.of("id", "user_id"));
        assertThat(sql).isEqualTo(
                "INSERT IGNORE INTO orders (id, user_id) VALUES (?, ?)");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.env.SqlDialectTest`
Expected: FAIL (컴파일).

- [ ] **Step 3: SqlDialect 작성**

```java
package io.graphrag.builder.env;

import java.util.List;

/** dialect별 SQL 조각. 현재는 멱등 INSERT만. */
public final class SqlDialect {

    private SqlDialect() {
    }

    public static String idempotentInsert(DbConfig.Type type, String table, List<String> columns) {
        String cols = String.join(", ", columns);
        String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        return switch (type) {
            case POSTGRES -> "INSERT INTO " + table + " (" + cols + ") VALUES ("
                    + placeholders + ") ON CONFLICT DO NOTHING";
            case MYSQL, MARIADB -> "INSERT IGNORE INTO " + table + " (" + cols + ") VALUES ("
                    + placeholders + ")";
        };
    }
}
```

- [ ] **Step 4: PASS + 커밋**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.env.SqlDialectTest`
Expected: PASS

```bash
git add graph-rag-builder
git commit -m "feat(builder): SqlDialect.idempotentInsert for postgres/mysql"
```

### Task B3: `AnalysisEnvironment` DB 비종속화 + `Seeds` dialect 적용

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/JdbcContainers.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/env/AnalysisEnvironment.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/Seeds.java`
- Modify: `graph-rag-builder/build.gradle.kts` (mysql testcontainers + 드라이버)

- [ ] **Step 1: 의존 추가**

`graph-rag-builder/build.gradle.kts`:

```kotlin
    implementation("org.testcontainers:mysql:1.20.4")
    implementation("com.mysql:mysql-connector-j:9.1.0")
```
(postgres testcontainers/드라이버는 이미 있음)

- [ ] **Step 2: JdbcContainers 작성**

```java
package io.graphrag.builder.env;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** DbConfig → type에 맞는 Testcontainers JDBC 컨테이너. compose와 동일 image 사용. */
public final class JdbcContainers {

    private JdbcContainers() {
    }

    public static JdbcDatabaseContainer<?> create(DbConfig config) {
        return switch (config.type()) {
            case POSTGRES -> new PostgreSQLContainer<>(DockerImageName.parse(config.image()))
                    .withDatabaseName(nz(config.dbName(), "app"))
                    .withUsername(nz(config.user(), "app"))
                    .withPassword(nz(config.password(), "app"));
            case MYSQL -> new MySQLContainer<>(DockerImageName.parse(config.image()))
                    .withDatabaseName(nz(config.dbName(), "app"))
                    .withUsername(nz(config.user(), "app"))
                    .withPassword(nz(config.password(), "app"));
            case MARIADB -> new MariaDBContainer<>(DockerImageName.parse(config.image()))
                    .withDatabaseName(nz(config.dbName(), "app"))
                    .withUsername(nz(config.user(), "app"))
                    .withPassword(nz(config.password(), "app"));
        };
    }

    private static String nz(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
```

- [ ] **Step 3: AnalysisEnvironment 수정**

`PostgreSQLContainer` import/필드를 `JdbcDatabaseContainer<?> db`로 교체. 생성자/유틸 교체:

```java
import org.testcontainers.containers.JdbcDatabaseContainer;

    private final JdbcDatabaseContainer<?> db;
    private final DbConfig dbConfig;

    public AnalysisEnvironment(DbConfig dbConfig) {
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.44");
        }
        this.dbConfig = dbConfig;
        this.db = JdbcContainers.create(dbConfig);
    }

    public DbConfig.Type dbType() {
        return dbConfig.type();
    }
```

`start(...)` 내부 `postgres.start()` → `db.start()`, `jdbcUrl()`은 `db.getJdbcUrl()`, `openConnection()`은 `DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())`, `close()`의 `postgres.stop()` → `db.stop()`. SUT 주입(`:61`)의 `"app","app"`도 `db.getUsername(), db.getPassword()`로 교체.

- [ ] **Step 4: Seeds.insert에 dialect 적용**

`Seeds.insert` 시그니처에 `DbConfig.Type type` 추가, 하드코딩 SQL을 `SqlDialect.idempotentInsert(type, seed.table(), seed.columns())`로 교체:

```java
    static void insert(Connection connection, DbConfig.Type type,
                       SynthesizedInput.SeedRow seed) throws Exception {
        String sql = SqlDialect.idempotentInsert(type, seed.table(), seed.columns());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < seed.values().size(); i++) {
                statement.setObject(i + 1, seed.values().get(i));
            }
            statement.executeUpdate();
        }
        log.info("seeded: {} {}", seed.table(), seed.values());
    }
```

`EndpointExplorationRunner.run`의 `Seeds.insert(connection, seed)` 호출부(`:90`)에 type 인자 추가 — runner는 type을 모르므로 생성자에 `DbConfig.Type dbType` 필드를 추가하고 `EndpointExplorationRunner`를 만드는 곳(Task에서 BuilderCli)에서 `env.dbType()` 전달. (이 Step에서는 runner 생성자에 필드 추가 + 호출부 보정까지)

- [ ] **Step 5: 컴파일 + 기존 builder 테스트 확인**

Run: `./gradlew :graph-rag-builder:compileJava :graph-rag-builder:compileTestJava`
Expected: BUILD SUCCESSFUL (BuilderCli는 Task B4에서 정리하므로, 이 시점에 BuilderCli 컴파일 에러가 나면 임시로 `new DbConfig(DbConfig.Type.POSTGRES, config.postgresImage(), "app","app","app")`를 넘겨 통과).

- [ ] **Step 6: 커밋**

```bash
git add graph-rag-builder
git commit -m "feat(builder): DB-agnostic AnalysisEnvironment via JdbcContainers + dialect seeds"
```

### Task B4: `BuilderCli` — `--sut-compose`로 DbConfig 주입

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java`

- [ ] **Step 1: BuildConfig에서 postgresImage → dbConfig**

`BuildConfig`의 `String postgresImage` 필드를 `DbConfig dbConfig`로 교체(생성자/축약 생성자 동반 수정).

- [ ] **Step 2: BuilderCli 옵션 파싱 교체**

`BuilderCli.java:69`의 `options.getOrDefault("--postgres-image", "postgres:15")` 제거. `--sut-compose` 경로를 읽어:

```java
        DbConfig dbConfig = ComposeInspector.detectDb(
                Path.of(options.get("--sut-compose")));
```
override 허용: `--db-image` 지정 시 `new DbConfig(dbConfig.type(), options.get("--db-image"), dbConfig.dbName(), dbConfig.user(), dbConfig.password())`.

`:125`의 `new AnalysisEnvironment(config.postgresImage())` → `new AnalysisEnvironment(config.dbConfig())`. `EndpointExplorationRunner` 생성 시 `env.dbType()` 전달(Task B3 Step4의 필드).

- [ ] **Step 3: 사용법 주석 갱신**

`BuilderCli.java:45`의 usage 문자열에서 `[--postgres-image postgres:15]` → `--sut-compose <docker-compose.yml> [--db-image <image>]`.

- [ ] **Step 4: 컴파일 + 커밋**

Run: `./gradlew :graph-rag-builder:compileJava`
Expected: BUILD SUCCESSFUL

```bash
git add graph-rag-builder
git commit -m "feat(builder): --sut-compose drives DbConfig, drop --postgres-image"
```

---

## Phase C — 다중 HTTP method 인덱싱

### Task C1: `EndpointIndexer` 다중 method + PathVariable/RequestParam

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/EndpointIndexer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/EndpointIndexerTest.java`

- [ ] **Step 1: 실패 테스트 추가**

`EndpointIndexerTest`에 fixture 컨트롤러 소스를 임시 디렉터리에 쓰고 인덱싱하는 케이스 추가:

```java
@Test
void indexesGetWithPathVariableAndRequestParam(@org.junit.jupiter.api.io.TempDir Path dir)
        throws Exception {
    Path src = dir.resolve("C.java");
    Files.writeString(src, """
            package x;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api/orders")
            class C {
                @GetMapping("/{id}")
                String get(@PathVariable Long id) { return null; }
                @GetMapping
                String list(@RequestParam Long userId) { return null; }
                @DeleteMapping("/{id}")
                void del(@PathVariable Long id) {}
            }
            """);
    EndpointIndexer.IndexResult result = new EndpointIndexer().index(dir, null);

    Endpoint get = result.endpoints().stream()
            .filter(e -> e.httpMethod().equals("GET") && e.path().equals("/api/orders/{id}"))
            .findFirst().orElseThrow();
    assertThat(get.params()).extracting(EndpointParam::kind).containsExactly(ParamKind.PATH);
    assertThat(get.authRequired()).isFalse();

    Endpoint list = result.endpoints().stream()
            .filter(e -> e.httpMethod().equals("GET") && e.path().equals("/api/orders"))
            .findFirst().orElseThrow();
    assertThat(list.params()).extracting(EndpointParam::kind).containsExactly(ParamKind.QUERY);

    assertThat(result.endpoints()).anyMatch(e -> e.httpMethod().equals("DELETE"));
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.index.EndpointIndexerTest`
Expected: FAIL (`index`가 1-인자, GET 미인덱싱).

- [ ] **Step 3: EndpointIndexer 일반화**

상수 추가 + method 매핑 + index 시그니처에 `AuthConfig authConfig`(nullable) 추가:

```java
    private static final java.util.Map<String, String> MAPPING_TO_METHOD = java.util.Map.of(
            "org.springframework.web.bind.annotation.GetMapping", "GET",
            "org.springframework.web.bind.annotation.PostMapping", "POST",
            "org.springframework.web.bind.annotation.PutMapping", "PUT",
            "org.springframework.web.bind.annotation.DeleteMapping", "DELETE",
            "org.springframework.web.bind.annotation.PatchMapping", "PATCH");
    private static final String PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable";
    private static final String REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam";

    public IndexResult index(Path sutSrcDir) {
        return index(sutSrcDir, null);
    }

    public IndexResult index(Path sutSrcDir, io.graphrag.builder.run.AuthConfig authConfig) {
        ...
            for (CtMethod<?> method : type.getMethods()) {
                String httpMethod = null;
                CtAnnotation<?> mapping = null;
                for (var entry : MAPPING_TO_METHOD.entrySet()) {
                    CtAnnotation<?> a = findAnnotation(method, entry.getKey());
                    if (a != null) { httpMethod = entry.getValue(); mapping = a; break; }
                }
                if (httpMethod == null) {
                    continue;
                }
                String fullPath = joinPaths(basePath, annotationPath(mapping));
                List<EndpointParam> params = extractParams(method, model, bodyShapes);
                endpoints.add(new Endpoint(
                        endpointId(httpMethod, fullPath), httpMethod, fullPath,
                        type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                        params, authRequired(fullPath, authConfig)));
            }
    }

    private static boolean authRequired(String path, io.graphrag.builder.run.AuthConfig authConfig) {
        return authConfig != null && !path.equals(authConfig.loginPath())
                && !authConfig.publicPaths().contains(path);
    }
```

`extractParams`에 PATH/QUERY 분기 추가:

```java
        for (CtParameter<?> parameter : method.getParameters()) {
            if (findAnnotation(parameter, REQUEST_BODY) != null) {
                String bodyType = parameter.getType().getQualifiedName();
                params.add(new EndpointParam(parameter.getSimpleName(), bodyType, ParamKind.BODY));
                extractBodyShape(model, bodyType).ifPresent(s -> bodyShapes.put(bodyType, s));
            } else if (findAnnotation(parameter, PATH_VARIABLE) != null) {
                params.add(new EndpointParam(parameter.getSimpleName(),
                        parameter.getType().getQualifiedName(), ParamKind.PATH));
            } else if (findAnnotation(parameter, REQUEST_PARAM) != null) {
                params.add(new EndpointParam(parameter.getSimpleName(),
                        parameter.getType().getQualifiedName(), ParamKind.QUERY));
            }
        }
```

> 참고: `AuthConfig`는 Task D1에서 생성한다. 본 태스크를 먼저 실행할 경우 `authConfig` 파라미터 타입을 임시로 정의하거나 D1을 선행할 것. 권장 실행 순서는 D1 → C1.

- [ ] **Step 4: PASS 확인 + 기존 POST 테스트 회귀 없음**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.index.EndpointIndexerTest`
Expected: PASS (기존 POST 케이스 포함).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder
git commit -m "feat(builder): EndpointIndexer indexes GET/PUT/DELETE/PATCH + PathVariable/RequestParam"
```

---

## Phase D — JWT 인증

### Task D1: `AuthConfig` + `AuthTokenProvider`

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/AuthConfig.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/AuthTokenProvider.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/AuthTokenProviderTest.java`

- [ ] **Step 1: 실패 테스트 (로컬 HttpServer가 토큰 반환)**

```java
package io.graphrag.builder.run;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenProviderTest {

    @Test
    void logsInOnceAndCachesToken() throws Exception {
        int[] hits = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", ex -> {
            hits[0]++;
            byte[] body = "{\"token\":\"jwt-abc\",\"type\":\"Bearer\"}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            AuthConfig config = new AuthConfig("/api/auth/login", "admin", "password",
                    "token", "Authorization", "Bearer", List.of());
            AuthTokenProvider provider = new AuthTokenProvider(base, config);
            assertThat(provider.token()).isEqualTo("jwt-abc");
            assertThat(provider.token()).isEqualTo("jwt-abc");
            assertThat(hits[0]).isEqualTo(1);   // 1회만 로그인
            assertThat(config.headerValue("jwt-abc")).isEqualTo("Bearer jwt-abc");
        } finally {
            server.stop(0);
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.run.AuthTokenProviderTest`
Expected: FAIL (컴파일).

- [ ] **Step 3: AuthConfig 작성**

```java
package io.graphrag.builder.run;

import java.util.List;

/** JWT 로그인 구성. petclinic ApiBlackBoxTestSupport 패턴의 파라미터화. */
public record AuthConfig(
        String loginPath, String username, String password,
        String tokenField, String headerName, String scheme,
        List<String> publicPaths) {

    public AuthConfig {
        publicPaths = publicPaths == null ? List.of() : publicPaths;
    }

    public String headerValue(String token) {
        return scheme + " " + token;
    }
}
```

- [ ] **Step 4: AuthTokenProvider 작성 (login 1회 캐싱)**

```java
package io.graphrag.builder.run;

import io.graphrag.model.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 탐색 시작 전 1회 로그인 → token 캐시 (빌드 런 전체 공유). */
public final class AuthTokenProvider {

    private final String baseUri;
    private final AuthConfig config;
    private final HttpClient http = HttpClient.newHttpClient();
    private volatile String cached;

    public AuthTokenProvider(String baseUri, AuthConfig config) {
        this.baseUri = baseUri;
        this.config = config;
    }

    public String token() {
        String local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached == null) {
                cached = login();
            }
            return cached;
        }
    }

    private String login() {
        try {
            String body = Json.mapper().writeValueAsString(java.util.Map.of(
                    "username", config.username(), "password", config.password()));
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUri + config.loginPath()))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("login failed: HTTP " + response.statusCode());
            }
            String token = Json.mapper().readTree(response.body())
                    .path(config.tokenField()).asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("token field '" + config.tokenField()
                        + "' missing in login response");
            }
            return token;
        } catch (Exception e) {
            throw new IllegalStateException("auth login failed", e);
        }
    }
}
```

- [ ] **Step 5: PASS + 커밋**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.run.AuthTokenProviderTest`
Expected: PASS

```bash
git add graph-rag-builder
git commit -m "feat(builder): AuthConfig + AuthTokenProvider (login-once cached token)"
```

### Task D2: `httpInvoker`에 Bearer 주입 + runner 배선

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`

- [ ] **Step 1: runner에 AuthTokenProvider 주입**

`EndpointExplorationRunner` 생성자에 `AuthTokenProvider authProvider`(nullable) 필드 추가. `httpInvoker(endpoint)` 내 요청 빌더에 조건부 헤더:

```java
                HttpRequest.Builder builder = HttpRequest.newBuilder(
                                URI.create(sut.baseUri() + endpoint.path()))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("baggage", "test-id=explore");
                if (authProvider != null && endpoint.authRequired()) {
                    builder.header(authConfig.headerName(),
                            authConfig.headerValue(authProvider.token()));
                }
```

(`authConfig`도 runner 필드로 보관)

- [ ] **Step 2: 컴파일 — BuilderCli 배선은 Task D5에서**

이 시점에 BuilderCli가 새 생성자 인자를 안 넘겨 컴파일 에러가 나면, BuilderCli에서 일단 `null`을 전달(인증 비활성)해 통과시킨다.

Run: `./gradlew :graph-rag-builder:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add graph-rag-builder
git commit -m "feat(builder): inject Bearer header for authRequired endpoints during exploration"
```

### Task D3: testlib `RealAuthAdapter` + `RestAssuredHelper.authenticated()`

**Files:**
- Create: `testlib/src/main/java/io/graphrag/testlib/adapter/real/RealAuthAdapter.java`
- Create: `testlib/src/main/java/io/graphrag/testlib/adapter/real/JwtAuthClient.java`
- Modify: `testlib/src/main/resources/META-INF/services/io.graphrag.testlib.spi.AuthAdapter`
- Modify: `testlib/src/main/java/io/graphrag/testlib/api/RestAssuredHelper.java`
- Modify: `testlib/src/main/java/io/graphrag/testlib/api/TestScope.java`
- Test: `testlib/src/test/java/io/graphrag/testlib/adapter/real/JwtAuthClientTest.java`

- [ ] **Step 1: 실패 테스트 (로컬 HttpServer)**

```java
package io.graphrag.testlib.adapter.real;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.testlib.spi.Env;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthClientTest {

    @Test
    void loginExtractsAndCachesToken() throws Exception {
        int[] hits = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", ex -> {
            hits[0]++;
            byte[] b = "{\"token\":\"jwt-xyz\"}".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            JwtAuthClient client = new JwtAuthClient(base, "/api/auth/login", "token");
            assertThat(client.login("admin", "password")).isEqualTo("jwt-xyz");
            assertThat(client.login("admin", "password")).isEqualTo("jwt-xyz");
            assertThat(hits[0]).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :testlib:test --tests io.graphrag.testlib.adapter.real.JwtAuthClientTest`
Expected: FAIL (컴파일).

- [ ] **Step 3: JwtAuthClient 작성 (double-checked locking 캐싱)**

```java
package io.graphrag.testlib.adapter.real;

import io.graphrag.model.Json;
import io.graphrag.testlib.api.AuthClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** petclinic ApiBlackBoxTestSupport.authToken() 패턴: login 1회 + volatile 캐싱. */
public final class JwtAuthClient implements AuthClient {

    private final String baseUri;
    private final String loginPath;
    private final String tokenField;
    private final HttpClient http = HttpClient.newHttpClient();
    private volatile String cached;

    public JwtAuthClient(String baseUri, String loginPath, String tokenField) {
        this.baseUri = baseUri;
        this.loginPath = loginPath;
        this.tokenField = tokenField;
    }

    @Override
    public String login(String username, String password) {
        String local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached == null) {
                cached = doLogin(username, password);
            }
            return cached;
        }
    }

    private String doLogin(String username, String password) {
        try {
            String body = Json.mapper().writeValueAsString(
                    Map.of("username", username, "password", password));
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUri + loginPath))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return Json.mapper().readTree(response.body()).path(tokenField).asText();
        } catch (Exception e) {
            throw new IllegalStateException("login failed", e);
        }
    }
}
```

- [ ] **Step 4: RealAuthAdapter 작성**

```java
package io.graphrag.testlib.adapter.real;

import io.graphrag.testlib.api.AuthClient;
import io.graphrag.testlib.spi.AuthAdapter;
import io.graphrag.testlib.spi.Env;

public final class RealAuthAdapter implements AuthAdapter {

    @Override
    public String name() {
        return "real";
    }

    @Override
    public AuthClient create(Env env) {
        return new JwtAuthClient(
                env.require("APP_BASE_URI"),
                env.getOrDefault("AUTH_LOGIN_PATH", "/api/auth/login"),
                env.getOrDefault("AUTH_TOKEN_FIELD", "token"));
    }
}
```

- [ ] **Step 5: SPI 등록**

`testlib/src/main/resources/META-INF/services/io.graphrag.testlib.spi.AuthAdapter`에 한 줄 추가:

```
io.graphrag.testlib.adapter.real.RealAuthAdapter
```

- [ ] **Step 6: RestAssuredHelper.authenticated() + TestScope 배선**

`RestAssuredHelper` 생성자에 `AuthClient auth`와 인증 구성 추가, `authenticated()` 메서드:

```java
    private final AuthClient auth;
    private final String headerName;
    private final String scheme;
    private final String username;
    private final String password;

    RestAssuredHelper(String baseUri, String testId, AuthClient auth,
                      String headerName, String scheme, String username, String password) {
        this.baseUri = baseUri;
        this.testId = testId;
        this.auth = auth;
        this.headerName = headerName;
        this.scheme = scheme;
        this.username = username;
        this.password = password;
    }

    public RequestSpecification authenticated() {
        return given().header(headerName, scheme + " " + auth.login(username, password));
    }
```

`TestScope.create`에서 `RestAssuredHelper` 생성을 갱신: `new RestAssuredHelper(appBaseUri, testId, authAdapter.create(env), env.getOrDefault("AUTH_HEADER","Authorization"), env.getOrDefault("AUTH_SCHEME","Bearer"), env.getOrDefault("AUTH_USER","admin"), env.getOrDefault("AUTH_PASS","password"))`. (기존 `auth` 필드는 그대로 두되 RestAssuredHelper와 동일 AuthClient 인스턴스를 공유하도록 변수로 추출)

- [ ] **Step 7: PASS + 기존 testlib 테스트 회귀 없음 + 커밋**

Run: `./gradlew :testlib:test`
Expected: PASS

```bash
git add testlib
git commit -m "feat(testlib): RealAuthAdapter + JwtAuthClient + RestAssuredHelper.authenticated()"
```

### Task D4: generator — `authRequired` → `authenticated()` 합성

**Files:**
- Modify: `test-generator/src/main/java/io/graphrag/generator/Generator.java`
- Modify: `test-generator/src/main/resources/templates/test-class.mustache`
- Test: `test-generator/src/test/java/io/graphrag/generator/GeneratorTest.java`

- [ ] **Step 1: 골든/단언 테스트 추가**

`GeneratorTest`에 authRequired 엔드포인트가 `authenticated()`를 내는지 검증(기존 골든 패턴 따라 fixture graph 구성). 핵심 단언:

```java
@Test
void authRequiredEndpoint_usesAuthenticatedHelper() {
    // authRequired=true인 endpoint를 가진 graph를 fixture로 로드
    GenerationResult result = generator.generate(requestForAuthEndpoint());
    String code = result.files().get(0).content();
    assertThat(code).contains("scope.rest().authenticated()");
    assertThat(code).doesNotContain("scope.rest().given()");
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :test-generator:test --tests io.graphrag.generator.GeneratorTest`
Expected: FAIL.

- [ ] **Step 3: 템플릿 분기 추가**

`test-class.mustache`의 `scope.rest().given()` 라인을 교체:

```
        {{#authRequired}}scope.rest().authenticated(){{/authRequired}}{{^authRequired}}scope.rest().given(){{/authRequired}}
```

- [ ] **Step 4: Generator.generateSingle에 scope 키 추가**

`Generator.generateSingle`에 `scope.put("authRequired", endpoint.authRequired());` 추가.

- [ ] **Step 5: PASS + 커밋**

Run: `./gradlew :test-generator:test --tests io.graphrag.generator.GeneratorTest`
Expected: PASS

```bash
git add test-generator
git commit -m "feat(generator): authRequired endpoints synthesize authenticated() requests"
```

### Task D5: BuilderCli 인증 옵션 배선

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java`

- [ ] **Step 1: BuildConfig에 AuthConfig 추가 (nullable)**

`BuildConfig`에 `AuthConfig authConfig` 필드 추가.

- [ ] **Step 2: BuilderCli 옵션 파싱**

`--auth-login-path` 가 주어지면:

```java
        AuthConfig authConfig = options.containsKey("--auth-login-path")
                ? new AuthConfig(options.get("--auth-login-path"),
                        options.getOrDefault("--auth-user", "admin"),
                        options.getOrDefault("--auth-pass", "password"),
                        options.getOrDefault("--auth-token-field", "token"),
                        options.getOrDefault("--auth-header", "Authorization"),
                        options.getOrDefault("--auth-scheme", "Bearer"),
                        List.of())
                : null;
```

`EndpointIndexer.index(srcDir, authConfig)` 호출에 authConfig 전달. 환경 start 후 `AuthTokenProvider provider = authConfig == null ? null : new AuthTokenProvider(env.sut().baseUri(), authConfig);` 생성, `EndpointExplorationRunner`에 `provider`, `authConfig` 전달.

- [ ] **Step 3: usage 주석 갱신 + 컴파일 + 커밋**

`BuilderCli.java:45` usage에 `[--auth-login-path /api/auth/login --auth-user admin --auth-pass password]` 추가.

Run: `./gradlew :graph-rag-builder:compileJava`
Expected: BUILD SUCCESSFUL

```bash
git add graph-rag-builder
git commit -m "feat(builder): --auth-* options wire AuthConfig into index + exploration"
```

---

## Phase E — GET read-path 시드 + 합성

### Task E1: `ReadInputSynthesizer`

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ReadInputSynthesizer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ReadInputSynthesizerTest.java`

- [ ] **Step 1: 실패 테스트**

```java
package io.graphrag.builder.run;

import io.graphrag.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReadInputSynthesizerTest {

    @Test
    void pathVariableSeedsTargetTableAndBuildsInput() {
        Endpoint endpoint = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}",
                "x.C", "get", List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)),
                true);
        TableSchema orders = new TableSchema("orders",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("status", "VARCHAR", false, false)),
                List.of(), List.of());

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(endpoint, List.of(orders));

        assertThat(out.body().get("id").asText()).isEqualTo("1");
        assertThat(out.seeds()).hasSize(1);
        SynthesizedInput.SeedRow seed = out.seeds().get(0);
        assertThat(seed.table()).isEqualTo("orders");
        assertThat(seed.columns()).contains("id");
        assertThat(seed.values()).contains("1");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.run.ReadInputSynthesizerTest`
Expected: FAIL.

- [ ] **Step 3: ReadInputSynthesizer 작성**

```java
package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import io.graphrag.model.TableSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * 조회(GET) 엔드포인트의 read-path 입력 + 시드를 결정적으로 합성한다.
 * path/query param을 WHERE 제약으로 보고, 타깃 테이블에 매칭 행을 시드한다.
 */
public class ReadInputSynthesizer {

    public SynthesizedInput synthesize(Endpoint endpoint, List<TableSchema> tables) {
        ObjectNode input = Json.mapper().createObjectNode();
        TableSchema target = resolveTargetTable(endpoint, tables);

        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (target != null) {
            for (ColumnSchema column : target.columns()) {
                if (!column.nullable() || column.primaryKey()) {
                    columns.add(column.name());
                    values.add(defaultFor(column));
                }
            }
        }

        for (EndpointParam param : endpoint.params()) {
            if (param.kind() != ParamKind.PATH && param.kind() != ParamKind.QUERY) {
                continue;
            }
            String value = scalarFor(param);
            input.put(param.name(), value);
            // param 이름을 타깃 컬럼(snake_case)에 매핑해 시드 값을 일치시킨다
            String column = mapParamToColumn(param.name(), target);
            if (column != null) {
                int idx = columns.indexOf(column);
                if (idx >= 0) {
                    values.set(idx, value);
                } else {
                    columns.add(column);
                    values.add(value);
                }
            }
        }

        List<SynthesizedInput.SeedRow> seeds = target == null ? List.of()
                : List.of(new SynthesizedInput.SeedRow(target.name(), columns, values));
        return new SynthesizedInput(input, seeds);
    }

    /** path 세그먼트/스키마로 타깃 테이블 추론: 경로에 테이블명(또는 +s)이 등장하는 첫 매칭. */
    private TableSchema resolveTargetTable(Endpoint endpoint, List<TableSchema> tables) {
        String path = endpoint.path().toLowerCase();
        for (TableSchema table : tables) {
            String name = table.name().toLowerCase();
            if (path.contains("/" + name) || path.contains("/" + singular(name))) {
                return table;
            }
        }
        return tables.isEmpty() ? null : null;
    }

    /** "id"/"xxxId" → PK 컬럼, 그 외 → snake_case 동일 컬럼이 있으면 그 컬럼. */
    private String mapParamToColumn(String paramName, TableSchema target) {
        if (target == null) {
            return null;
        }
        if (paramName.equals("id") || paramName.endsWith("Id")) {
            return target.columns().stream().filter(ColumnSchema::primaryKey)
                    .map(ColumnSchema::name).findFirst().orElse(null);
        }
        String snake = camelToSnake(paramName);
        return target.columns().stream().map(ColumnSchema::name)
                .filter(snake::equals).findFirst().orElse(null);
    }

    private static String scalarFor(EndpointParam param) {
        return switch (param.javaType()) {
            case "java.lang.Integer", "int", "java.lang.Long", "long" -> "1";
            default -> "probe-" + param.name();
        };
    }

    private static Object defaultFor(ColumnSchema column) {
        String type = column.jdbcType();
        if (type.contains("CHAR") || type.contains("TEXT")) return "probe";
        if (type.contains("BOOL")) return true;
        return 1;
    }

    private static String singular(String name) {
        return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
    }

    static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
```

- [ ] **Step 4: PASS + 커밋**

Run: `./gradlew :graph-rag-builder:test --tests io.graphrag.builder.run.ReadInputSynthesizerTest`
Expected: PASS

```bash
git add graph-rag-builder
git commit -m "feat(builder): ReadInputSynthesizer — schema+param seed for GET read-paths"
```

### Task E2: `httpInvoker` 메서드 분배 + runner read 분기 + RequiredSeed 기록

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`

- [ ] **Step 1: httpInvoker를 method/param-aware로 일반화**

`httpInvoker(endpoint)`에서 URL과 method를 input에 따라 조립:

```java
    private EndpointInvoker httpInvoker(Endpoint endpoint) {
        HttpClient http = HttpClient.newHttpClient();
        return input -> {
            try {
                long logStart = sut.logOffset();
                String url = sut.baseUri() + buildPathAndQuery(endpoint, input);
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("baggage", "test-id=explore");
                if (authProvider != null && endpoint.authRequired()) {
                    builder.header(authConfig.headerName(),
                            authConfig.headerValue(authProvider.token()));
                }
                String method = endpoint.httpMethod();
                if (method.equals("GET") || method.equals("DELETE")) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.method(method, HttpRequest.BodyPublishers.ofString(
                            Json.mapper().writeValueAsString(bodyOnly(endpoint, input))));
                }
                HttpResponse<String> response = http.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                Thread.sleep(150);
                BranchCoverage requestCoverage = analyzer.analyze(coverage.dump(true));
                long logEnd = sut.logOffset();
                return new InvocationOutcome(response.statusCode(),
                        parseJsonOrNull(response.body()), requestCoverage.covered(),
                        logStart, logEnd,
                        httpCapture == null ? List.of() : httpCapture.drainNewExchanges());
            } catch (Exception e) {
                throw new IllegalStateException("invocation failed: " + endpoint.path(), e);
            }
        };
    }

    /** PATH param은 {name} 치환, QUERY param은 쿼리스트링으로. */
    private static String buildPathAndQuery(Endpoint endpoint, com.fasterxml.jackson.databind.JsonNode input) {
        String path = endpoint.path();
        StringBuilder query = new StringBuilder();
        for (io.graphrag.model.EndpointParam param : endpoint.params()) {
            if (!input.has(param.name())) continue;
            String value = input.get(param.name()).asText();
            if (param.kind() == io.graphrag.model.ParamKind.PATH) {
                path = path.replace("{" + param.name() + "}", value);
            } else if (param.kind() == io.graphrag.model.ParamKind.QUERY) {
                query.append(query.isEmpty() ? "?" : "&")
                        .append(param.name()).append("=").append(value);
            }
        }
        return path + query;
    }

    /** BODY param 필드만 남긴 ObjectNode (path/query 필드 제거). */
    private static com.fasterxml.jackson.databind.JsonNode bodyOnly(Endpoint endpoint,
            com.fasterxml.jackson.databind.JsonNode input) {
        java.util.Set<String> nonBody = new java.util.HashSet<>();
        for (io.graphrag.model.EndpointParam param : endpoint.params()) {
            if (param.kind() != io.graphrag.model.ParamKind.BODY) {
                nonBody.add(param.name());
            }
        }
        ObjectNode body = input.deepCopy();
        nonBody.forEach(body::remove);
        return body;
    }
```

(상단 import에 `com.fasterxml.jackson.databind.node.ObjectNode` 추가)

- [ ] **Step 2: run()에 read 분기 + RequiredSeed 기록**

`run()` 시작부의 입력 합성을 분기:

```java
    public EndpointResult run(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                              List<ConstraintExtractor.ConditionSpan> conditions) throws Exception {
        boolean readPath = endpoint.httpMethod().equals("GET");
        SynthesizedInput happy = readPath
                ? new ReadInputSynthesizer().synthesize(endpoint, tables)
                : new SampleInputSynthesizer().synthesize(shape, tables);

        List<io.graphrag.model.RequiredSeed> requiredSeeds = new ArrayList<>();
        int seedSeq = 0;
        for (SynthesizedInput.SeedRow seed : happy.seeds()) {
            Seeds.insert(connection, dbType, seed);
            if (readPath) {
                seedSeq++;
                requiredSeeds.add(new io.graphrag.model.RequiredSeed(
                        "seed-" + endpoint.id() + "-" + seedSeq, null, seed.table(),
                        seed.columns(), seed.values().stream().map(String::valueOf).toList()));
            }
        }
        ...
```

`ExploredPath` 생성(`:111`)의 마지막 인자를 read-path면 해당 path의 seed id 목록으로:

```java
            List<String> seedIdsForPath = readPath
                    ? requiredSeeds.stream().map(io.graphrag.model.RequiredSeed::id).toList()
                    : List.of();
            paths.add(new ExploredPath(
                    candidate.pathId(), endpoint.id(), candidate.body(), candidate.status(),
                    candidate.response(),
                    sql.stream().map(CapturedSql::id).toList(),
                    httpCalls.stream().map(io.graphrag.model.CapturedHttpCall::id).toList(),
                    candidate.branches(), candidate.discoveredBy(),
                    matchConstraints(candidate, conditions, endpoint), validate(sql),
                    seedIdsForPath));
```

`EndpointResult`에 `List<RequiredSeed> seeds` 추가하고 반환. (record 컴포넌트 추가 + `return new EndpointResult(paths, allSql, allHttpCalls, requiredSeedsWithPathId, report(...))` — pathId는 첫 path id로 채워 재구성)

> 주: `RequiredSeed.pathId`는 path 확정 후 채운다. 단순화를 위해 read-path 엔드포인트는 happy path 1개를 기준으로 seed의 pathId를 그 path id로 설정한다.

- [ ] **Step 3: 컴파일 + 기존 POST e2e 단위 회귀 확인**

Run: `./gradlew :graph-rag-builder:compileJava :graph-rag-builder:test`
Expected: 기존 테스트 PASS (POST 경로 불변).

- [ ] **Step 4: 커밋**

```bash
git add graph-rag-builder
git commit -m "feat(builder): method-aware invoker + GET read-path branch records RequiredSeed"
```

### Task E3: BuilderCli — RequiredSeed를 GraphAsset에 수집

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`

- [ ] **Step 1: EndpointResult.seeds를 모아 GraphAsset.seeds로**

BuilderCli의 endpoint 루프에서 `result.seeds()`를 누적, `GraphAsset` 조립 시 마지막 인자(Task A1 Step6의 임시 `List.of()`)를 누적 리스트로 교체.

- [ ] **Step 2: 컴파일 + 커밋**

Run: `./gradlew :graph-rag-builder:compileJava`
Expected: BUILD SUCCESSFUL

```bash
git add graph-rag-builder
git commit -m "feat(builder): collect RequiredSeed into GraphAsset.seeds"
```

### Task E4: generator — read-path 시드 INSERT + GET 리터럴 URL

**Files:**
- Modify: `test-generator/src/main/java/io/graphrag/generator/compose/FixtureComposer.java`
- Modify: `test-generator/src/main/java/io/graphrag/generator/Generator.java`
- Modify: `test-generator/src/main/resources/templates/test-class.mustache`
- Modify: `test-generator/src/main/java/io/graphrag/generator/client/*` (GraphRagClient에 seeds 접근)
- Test: `test-generator/src/test/java/io/graphrag/generator/GeneratorTest.java`

- [ ] **Step 1: 골든 테스트 — GET read-path가 seed insert + 리터럴 URL + 단언 생성**

```java
@Test
void getReadPath_insertsSeedAndUsesLiteralUrl() {
    GenerationResult result = generator.generate(requestForGetEndpoint());
    String code = result.files().get(0).content();
    assertThat(code).contains("scope.jdbc().update(\"INSERT INTO orders");
    assertThat(code).contains(".get(\"/api/orders/1\")");
    assertThat(code).doesNotContain(".body(");   // GET은 body 없음
    assertThat(code).contains(".statusCode(200)");
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :test-generator:test --tests io.graphrag.generator.GeneratorTest`
Expected: FAIL.

- [ ] **Step 3: GraphRagClient에 seeds/seedsForPath 추가**

`GraphRagClient` 인터페이스 + `FileGraphRagClient` 구현에 `List<RequiredSeed> seedsForPath(String pathId)` 추가(graph.seeds()에서 pathId 매칭 필터).

- [ ] **Step 4: FixtureComposer가 RequiredSeed를 inserts로**

`compose(...)` 시그니처에 `List<RequiredSeed> seeds` 추가. read-path(seeds 비어있지 않음)면 §2의 SELECT-기반 seeding 대신 RequiredSeed에서 직접 insert/delete 생성:

```java
        if (!seeds.isEmpty()) {
            List<ComposedFixture.Stmt> seedInserts = seeds.stream()
                    .map(s -> new ComposedFixture.Stmt(
                            "INSERT INTO " + s.table() + " ("
                                    + String.join(", ", s.columns()) + ") VALUES ("
                                    + String.join(", ", s.columns().stream().map(c -> "?").toList())
                                    + ")",
                            s.values().stream().map(v -> "\"" + v + "\"").toList()))
                    .toList();
            List<ComposedFixture.Stmt> seedDeletes = seeds.stream()
                    .map(s -> new ComposedFixture.Stmt(
                            "DELETE FROM " + s.table() + " WHERE " + s.columns().get(0) + " = ?",
                            List.of("\"" + s.values().get(0) + "\"")))
                    .toList();
            // body 없음, 단언은 sampleResponse에서(기존 §5 로직 재사용)
            return new ComposedFixture(List.of(), seedInserts, seedDeletes,
                    "", List.of(), assertionsFromResponse(path));
        }
```

(기존 §5 단언 블록을 `assertionsFromResponse(path)` private 메서드로 추출해 재사용)

- [ ] **Step 5: Generator — 리터럴 URL 선계산 + read 분기 scope**

`Generator.generateSingle`에서 read-path면 `requestPath`를 sampleInput으로 치환해 리터럴화:

```java
        boolean readPath = endpoint.httpMethod().equals("GET");
        scope.put("readPath", readPath);
        scope.put("requestPath", readPath
                ? resolveLiteralPath(endpoint, path.sampleInput()) : endpoint.path());
        ComposedFixture fixture = new FixtureComposer().compose(
                path, sql, client.tables(), client.seedsForPath(pathId));
```

`resolveLiteralPath`는 Task E2의 `buildPathAndQuery`와 동일 규칙(여기선 generator쪽 헬퍼로 복제 — DRY를 위해 shared-model에 둘 수도 있으나 본 단계에선 generator 로컬):

```java
    private static String resolveLiteralPath(Endpoint endpoint, JsonNode input) {
        String path = endpoint.path();
        StringBuilder query = new StringBuilder();
        for (EndpointParam p : endpoint.params()) {
            if (!input.has(p.name())) continue;
            String v = input.get(p.name()).asText();
            if (p.kind() == ParamKind.PATH) path = path.replace("{" + p.name() + "}", v);
            else if (p.kind() == ParamKind.QUERY)
                query.append(query.isEmpty() ? "?" : "&").append(p.name()).append("=").append(v);
        }
        return path + query;
    }
```

- [ ] **Step 6: 템플릿 — read는 body 생략 + requestPath**

`test-class.mustache`의 요청 블록:

```
    @Test
    void {{testMethodName}}() {
        {{#authRequired}}scope.rest().authenticated(){{/authRequired}}{{^authRequired}}scope.rest().given(){{/authRequired}}
            .contentType("application/json"){{^readPath}}
            .body({{{bodyExpr}}}){{/readPath}}
        .when()
            .{{httpMethodLower}}("{{requestPath}}")
        .then()
            .statusCode({{expectedStatus}}){{{assertionsBlock}}};
    }
```

(`endpointPath` → `requestPath`로 교체)

- [ ] **Step 7: PASS + 결정성/기존 골든 회귀 + 커밋**

Run: `./gradlew :test-generator:test`
Expected: PASS (기존 POST 골든 포함 — POST는 readPath=false라 불변)

```bash
git add test-generator
git commit -m "feat(generator): read-path seed inserts + literal GET URL + no-body template"
```

---

## Phase F — 샘플 SUT 확장 (order-service)

### Task F1: JWT 로그인 + Security

**Files:**
- Modify: `samples/order-service/build.gradle.kts` (spring-security + jjwt)
- Create: `samples/order-service/src/main/java/.../auth/AuthController.java`
- Create: `samples/order-service/src/main/java/.../auth/JwtUtil.java`
- Create: `samples/order-service/src/main/java/.../auth/SecurityConfig.java`
- Create: `samples/order-service/src/main/java/.../auth/JwtFilter.java`
- Test: `samples/order-service/src/test/java/.../auth/AuthFlowTest.java` (Testcontainers)

- [ ] **Step 1: 의존 추가**

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
```

- [ ] **Step 2: 실패 테스트 (login → token → 보호 GET 통과)**

```java
@Test
void loginReturnsTokenAndProtectsGet() {
    // SUT를 띄운 뒤(@SpringBootTest, 랜덤 포트)
    String token = given().contentType("application/json")
            .body("{\"username\":\"admin\",\"password\":\"password\"}")
        .when().post("/api/auth/login")
        .then().statusCode(200).extract().path("token");

    // 토큰 없이 보호 GET → 401/403
    when().get("/api/orders/1").then().statusCode(anyOf(is(401), is(403)));
    // 토큰 있으면 통과(데이터 없으면 404 허용)
    given().header("Authorization", "Bearer " + token)
        .when().get("/api/orders/1")
        .then().statusCode(anyOf(is(200), is(404)));
}
```

- [ ] **Step 3: JwtUtil / AuthController / JwtFilter / SecurityConfig 구현**

petclinic 패턴 동형: `POST /api/auth/login`이 `admin/password` 검증 후 `{token,type:"Bearer",expiresIn:86400}` 반환. `JwtFilter`가 `Authorization: Bearer`를 검증. `SecurityConfig`는 `/api/auth/**` permitAll, 그 외 authenticated. (코드는 jjwt 0.12 API로 작성 — `Jwts.builder().subject(user).signWith(key).compact()`)

- [ ] **Step 4: PASS + 커밋**

Run: `./gradlew :samples:order-service:test`
Expected: PASS

```bash
git add samples/order-service
git commit -m "feat(sample): JWT login + Spring Security (admin/password, /api/auth/** public)"
```

### Task F2: GET 조회 엔드포인트 추가

**Files:**
- Modify: `samples/order-service/src/main/java/.../OrderController.java` (또는 신규 조회 컨트롤러)
- Test: `samples/order-service/src/test/java/.../OrderQueryTest.java`

- [ ] **Step 1: 실패 테스트 — seed 후 GET 조회**

```java
@Test
void getByIdReturnsSeededOrder() {
    // jdbc로 orders 행 1개 seed (id=1) 후
    given().header("Authorization", "Bearer " + token())
        .when().get("/api/orders/1")
        .then().statusCode(200).body("id", equalTo(1));
}
```

- [ ] **Step 2: GET 핸들러 구현**

`@GetMapping("/api/orders/{id}")` → `OrderRepository.findById`. `@GetMapping("/api/orders")` `@RequestParam Long userId` → `findByUserId`.

- [ ] **Step 3: PASS + 커밋**

Run: `./gradlew :samples:order-service:test`
Expected: PASS

```bash
git add samples/order-service
git commit -m "feat(sample): GET /api/orders/{id} and /api/orders?userId (protected)"
```

---

## Phase G — e2e 통합 + 문서

### Task G1: e2e compose/스크립트에 auth + compose-DB 배선

**Files:**
- Modify: `e2e/docker-compose.yml`
- Modify: `e2e/run-e2e.sh`
- Modify: `e2e/request-*.json` (read/auth 엔드포인트용 신규 요청)

- [ ] **Step 1: compose/스크립트 갱신**

`docker-compose.yml`의 app에 보안 env(없으면 기본 동작). `run-e2e.sh`: builder 호출에 `--sut-compose e2e/docker-compose.yml --auth-login-path /api/auth/login --auth-user admin --auth-pass password` 추가. runner/generated-test 실행 env에 `AUTH_ADAPTER=real AUTH_LOGIN_PATH=/api/auth/login AUTH_USER=admin AUTH_PASS=password` 추가.

- [ ] **Step 2: read/auth 요청 JSON 추가**

`request-orders-get.json` 등에 GET 엔드포인트 + `authMode:"REAL"` 명시.

- [ ] **Step 3: e2e 실행**

Run: `bash e2e/run-e2e.sh`
Expected: 전 경로 GREEN (기존 16 + read/auth 신규 케이스), 생성 테스트 컴파일·통과.

- [ ] **Step 4: 커밋**

```bash
git add e2e
git commit -m "feat(e2e): auth + compose-driven DB + GET read-path full cycle"
```

### Task G2: progress + decisions + README

**Files:**
- Create: `progress/4-*.md` (각 Phase 기록)
- Create: `docs/decisions/auth-required-heuristic.md`, `docs/decisions/read-target-resolution.md`, `docs/decisions/db-from-compose.md`
- Modify: `README.md`

- [ ] **Step 1: 기록 작성 + 커밋**

```bash
git add progress docs README.md
git commit -m "docs: progress/decisions/README for multi-method+readpath+auth+db"
```

---

## 자기 검토 메모 (작성자 확인 완료)

- **Spec 커버리지:** §3→C1, §4→A1/E1/E2/E3/E4, §5→D1~D5/F1, §6→B1~B4, §8→F1/F2/G1. 전 항목 태스크 대응.
- **타입 일관성:** `AuthConfig`(loginPath/username/password/tokenField/headerName/scheme/publicPaths), `DbConfig`(type/image/dbName/user/password), `RequiredSeed`(id/pathId/table/columns/values), `ExploredPath.requiredSeedIds` — 전 태스크에서 동일 시그니처 사용.
- **실행 순서 주의:** D1(AuthConfig)을 C1보다 먼저 실행(또는 C1에서 AuthConfig 참조부를 D1 직후 연결). A1의 임시 `List.of()`는 E2/E3에서 실제 값으로 교체.
- **보류:** authRequired 정적판정·read 타깃 추론·petclinic 2단계는 spec 보류 표대로 decision 문서화.

---

## 2단계 예고 (별도 plan)

`spring-petclinic`(외부 SUT) 적용: repo clone/build, petclinic의 `docker-compose.yml`로 `--sut-compose` 지정, `/api/auth/login`(admin/password) 인증, owner/pet/vet GET read-path. H2 vs compose-DB는 본 plan의 DB 비종속으로 자동 흡수. 별도 `docs/superpowers/plans/2026-XX-XX-petclinic-apply.md`로 작성 예정.
