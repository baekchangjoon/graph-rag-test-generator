# 정적 인덱싱 증분화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** graph-rag-builder의 정적 인덱싱을 (1) 단일 Spoon 모델 공유로 7→1회로 줄이고, (2) whole-result 캐시로 무변경 재빌드 시 Spoon 0회로 만든다.

**Architecture:** `SharedSpoonModel`이 SUT 소스를 1회 파싱해 모든 Spoon 인덱서에 `CtModel`을 공유한다(Stage 1). `IndexCache`가 소스 파일 내용 해시 매니페스트로 무변경을 판정해, 무변경이면 직렬화된 `StaticIndex`를 복원하고(Spoon 0회), 변경이 있으면 전체 재인덱싱 후 캐시를 원자적으로 재작성한다(Stage 2). cross-file 정확성을 위해 변경 시에도 전체 모델을 빌드한다(증분 == 풀 리빌드).

**Tech Stack:** Java 17+, Spoon(noClasspath), Jackson(`io.graphrag.model.Json`), JUnit 5.

## Global Constraints

- 출처 design spec: `docs/superpowers/specs/2026-06-22-incremental-static-index-design.md`
- 출처 요구사항명세: `docs/superpowers/requirements/2026-06-22-incremental-static-index-requirements.md`
- 범위: C안(whole-result). 부분모델(B/A안)·탐색단계 Spoon(ConstraintExtractor 등)은 범위 밖.
- 모든 Spoon 인덱서 Launcher 설정 고정: `noClasspath=true`, `commentEnabled=false`, `complianceLevel=17`.
- 기존 `index(Path)`/`extract(Path)` 시그니처·동작은 보존(하위호환).
- 커밋 author: `baekchangjoon <changjoon.baek@icloud.com>` (env vars로).
- 빌드/테스트: `./gradlew :graph-rag-builder:test`.

---

### Task 1: SharedSpoonModel 헬퍼

**REQ-IDs:** REQ-001, REQ-011

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/SharedSpoonModel.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/SharedSpoonModelTest.java`

**Interfaces:**
- Produces:
  - `static CtModel SharedSpoonModel.build(Path srcDir)` — Launcher 설정 통일 + 빌드, `BUILD_COUNT` 증가.
  - `static int SharedSpoonModel.buildCount()` / `static void SharedSpoonModel.resetBuildCount()` — 계측.

- [ ] **Step 1: 실패 테스트 작성**

`SharedSpoonModelTest.java`:
```java
package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import spoon.reflect.CtModel;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SharedSpoonModelTest {

    @Test
    void buildIncrementsCountAndParsesTypes() throws Exception {
        Path src = Files.createTempDirectory("shared-spoon");
        Files.writeString(src.resolve("Foo.java"), "package p; class Foo {}");
        SharedSpoonModel.resetBuildCount();

        CtModel model = SharedSpoonModel.build(src);

        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);
        assertThat(model.getAllTypes()).anyMatch(t -> t.getSimpleName().equals("Foo"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*SharedSpoonModelTest'`
Expected: FAIL — `SharedSpoonModel` 클래스 없음(컴파일 에러).

- [ ] **Step 3: 최소 구현**

`SharedSpoonModel.java`:
```java
package io.graphrag.builder.index;

import spoon.Launcher;
import spoon.reflect.CtModel;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** 정적 인덱서들이 공유하는 단일 Spoon 모델 빌더. Launcher 설정을 한 곳에 모은다. */
public final class SharedSpoonModel {

    private static final AtomicInteger BUILD_COUNT = new AtomicInteger();

    private SharedSpoonModel() {
    }

    public static CtModel build(Path srcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();
        BUILD_COUNT.incrementAndGet();
        return model;
    }

    public static int buildCount() {
        return BUILD_COUNT.get();
    }

    public static void resetBuildCount() {
        BUILD_COUNT.set(0);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*SharedSpoonModelTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/SharedSpoonModel.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/SharedSpoonModelTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(builder): SharedSpoonModel 단일 모델 빌더 + buildCount 계측"
```

---

### Task 2: 7개 Spoon 인덱서에 CtModel 오버로드 추가

**REQ-IDs:** REQ-002, REQ-011

**Files (각 파일 Modify):**
- `index/EndpointIndexer.java`, `index/RouterFunctionIndexer.java`, `index/GatewayRouteIndexer.java`,
  `index/WsEndpointIndexer.java`, `index/KafkaListenerIndexer.java`, `index/ResponseDtoIndexer.java`,
  `index/EnumConstantExtractor.java`
- Test: 기존 인덱서 단위 테스트 전부(회귀).

**Interfaces:**
- Produces (오버로드 추가):
  - `IndexResult EndpointIndexer.index(CtModel model, AuthConfig auth)`
  - `IndexResult RouterFunctionIndexer.index(CtModel model)`
  - `IndexResult GatewayRouteIndexer.index(CtModel model)`
  - `WsIndexResult WsEndpointIndexer.index(CtModel model)`
  - `KafkaIndexResult KafkaListenerIndexer.index(CtModel model)`
  - `List<Set<String>> ResponseDtoIndexer.extract(CtModel model)`
  - `Map<String,List<String>> EnumConstantExtractor.extract(CtModel model)`
- 기존 `index(Path)`/`extract(Path)`는 보존하고 내부에서 `SharedSpoonModel.build(path)` → 신규 오버로드 위임.

**변환 규칙 (7개 모두 동일 패턴):** 각 인덱서의 진입 메서드에서 처음 6줄(`Launcher launcher = new Launcher();` ~ `CtModel model = launcher.buildModel();`)을 제거하고, 본문을 `CtModel model`을 파라미터로 받는 새 오버로드로 옮긴다. 기존 `Path` 메서드는 `SharedSpoonModel.build(path)`를 호출해 위임한다.

- [ ] **Step 1: 대표 변환 — EndpointIndexer**

`EndpointIndexer.java` (현재 L54-64 영역). 기존:
```java
    public IndexResult index(Path sutSrcDir) {
        return index(sutSrcDir, null);
    }

    public IndexResult index(Path sutSrcDir, AuthConfig authConfig) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sutSrcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();
        ConverterRegistryIndexer.Registry converterRegistry = new ConverterRegistryIndexer().index(model);
        // ... 이하 본문 ...
```
변경 후:
```java
    public IndexResult index(Path sutSrcDir) {
        return index(sutSrcDir, null);
    }

    public IndexResult index(Path sutSrcDir, AuthConfig authConfig) {
        return index(SharedSpoonModel.build(sutSrcDir), authConfig);
    }

    public IndexResult index(CtModel model, AuthConfig authConfig) {
        ConverterRegistryIndexer.Registry converterRegistry = new ConverterRegistryIndexer().index(model);
        // ... 이하 본문 그대로 ...
```
사용하지 않게 된 `import spoon.Launcher;`는 제거한다(`CtModel` import는 유지).

- [ ] **Step 2: 나머지 6개 인덱서 동일 변환**

각 인덱서 진입 메서드(RouterFunctionIndexer.index L41, GatewayRouteIndexer.index L49, WsEndpointIndexer.index L33, KafkaListenerIndexer.index L32, ResponseDtoIndexer.extract L28, EnumConstantExtractor.extract L19)에 대해 동일 규칙 적용. 예 — `WsEndpointIndexer`:
```java
    public WsIndexResult index(Path sutSrcDir) {
        return index(SharedSpoonModel.build(sutSrcDir));
    }

    public WsIndexResult index(CtModel model) {
        String wsPath = configLiteral(model, "addEndpoint", "/ws");
        String appPrefix = configLiteral(model, "setApplicationDestinationPrefixes", "/app");
        // ... 이하 본문 그대로 ...
```
`ResponseDtoIndexer`/`EnumConstantExtractor`는 메서드명이 `extract`다(`extract(Path)` → `extract(CtModel)` 위임). 각 파일에서 미사용 `import spoon.Launcher;` 제거.

- [ ] **Step 3: 회귀 테스트 실행(인덱서 단위 테스트 green 유지)**

Run: `./gradlew :graph-rag-builder:test --tests '*Indexer*' --tests '*Extractor*'`
Expected: PASS — 기존 인덱서 테스트가 `index(Path)` 경로로 그대로 통과(REQ-011).

- [ ] **Step 4: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/*.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "refactor(builder): 7개 Spoon 인덱서에 index(CtModel) 오버로드 추가(하위호환 유지)"
```

---

### Task 3: build()에 모델 공유 배선 (Stage 1) + fullReindex 헬퍼

**REQ-IDs:** REQ-001, REQ-002

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (L162-231 영역)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderStaticIndexTest.java` (Create)

**Interfaces:**
- Produces: `private static StaticIndex BuilderCli.fullReindex(BuildConfig config)` — 단일 모델로 전체
  정적 산출물 생성. (`StaticIndex`는 Task 4에서 정의되지만, Task 3에서는 임시로 build() 인라인으로
  모델 공유만 적용하고, Task 7에서 `fullReindex`로 추출한다. **본 Task 범위 = 모델 공유 인라인 적용.**)

> 주: Task 3은 Stage 1(모델 공유)만 한다. `StaticIndex`/캐시는 Task 4~7. 따라서 여기서는 build()에서
> 모델을 1회 빌드해 7개 인덱서에 주입하도록만 고친다.

- [ ] **Step 1: 실패 테스트 작성 (정적 블록 buildCount==1, REQ-001)**

`BuilderStaticIndexTest.java`:
```java
package io.graphrag.builder.cli;

import io.graphrag.builder.index.SharedSpoonModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BuilderStaticIndexTest {

    @Test
    void staticIndexingBuildsSpoonModelOnce() throws Exception {
        Path src = Files.createTempDirectory("sut-src");
        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/foo\") String foo(){return \"x\";} }");
        SharedSpoonModel.resetBuildCount();

        BuilderCli.indexStatically(src);   // Task 3에서 노출하는 정적 인덱싱 진입(테스트 훅)

        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*BuilderStaticIndexTest'`
Expected: FAIL — `BuilderCli.indexStatically` 없음.

- [ ] **Step 3: build()의 정적 인덱싱 블록을 모델 공유로 변경**

`BuilderCli.build()`의 L164-182 + L230-231(enumConstants)을 다음 패키지-프라이빗 헬퍼로 추출하고
build()는 이를 호출하도록 고친다. `EnumConstantExtractor` 호출을 정적 블록으로 앞당긴다.
```java
    /** 정적 인덱싱 블록: SUT 소스를 1회 파싱해 모든 Spoon 인덱서가 공유. (테스트 훅 겸용) */
    static StaticIndexBundle indexStatically(Path sutSrc, Path sutResources, AuthConfig authConfig) {
        spoon.reflect.CtModel model = SharedSpoonModel.build(sutSrc);
        IndexResult index = new EndpointIndexer().index(model, authConfig);
        IndexResult functional = new RouterFunctionIndexer().index(model);
        if (!functional.endpoints().isEmpty()) { index = index.merge(functional); }
        IndexResult gateway = new GatewayRouteIndexer().index(model);
        if (!gateway.endpoints().isEmpty()) { index = index.merge(gateway); }
        WsIndexResult ws = new WsEndpointIndexer().index(model);
        KafkaIndexResult kafka = new KafkaListenerIndexer().index(model);
        List<Set<String>> dto = new ResponseDtoIndexer().extract(model);
        Map<String, List<String>> enums = new EnumConstantExtractor().extract(model);
        List<MapperStatement> mappers = Files.isDirectory(sutResources)
                ? new MapperXmlIndexer().index(sutResources) : List.<MapperStatement>of();
        return new StaticIndexBundle(index, ws, kafka, mappers, dto, enums);
    }

    /** 테스트 전용 단순 오버로드. */
    static StaticIndexBundle indexStatically(Path sutSrc) {
        return indexStatically(sutSrc, sutSrc.resolveSibling("resources"), null);
    }

    /** 정적 인덱싱 산출물 묶음(직렬화는 Task 4에서 record로 승격). */
    record StaticIndexBundle(IndexResult index, WsIndexResult ws, KafkaIndexResult kafka,
            List<MapperStatement> mappers, List<Set<String>> responseDtoFieldSets,
            Map<String, List<String>> enumConstants) {
    }
```
그리고 build() 본문에서 기존 L164-182 + L230-231 호출들을 제거하고:
```java
        StaticIndexBundle si = indexStatically(config.sutSrc(), config.sutResources(), config.authConfig());
        IndexResult index = si.index();
        io.graphrag.builder.index.WsIndexResult wsIndex = si.ws();
        io.graphrag.builder.index.KafkaIndexResult kafkaIndex = si.kafka();
        List<MapperStatement> mappers = si.mappers();
        List<Set<String>> responseDtoFieldSets = si.responseDtoFieldSets();
        Map<String, List<String>> enumConstants = si.enumConstants();
```
로 대체한다(이후 L186~ plan 계산, L210 mybatisLogLevels 등 기존 코드는 이 변수들을 그대로 사용).
기존 L230 `enumConstants` 추출 라인은 삭제(앞으로 이동했으므로).

- [ ] **Step 4: 테스트 통과 + 기존 빌더 테스트 회귀**

Run: `./gradlew :graph-rag-builder:test --tests '*BuilderStaticIndexTest' --tests '*BuilderCli*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderStaticIndexTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(builder): build() 정적 인덱싱 단일 모델 공유(Stage 1, 7→1회)"
```

---

### Task 4: StaticIndex + IndexManifest 직렬화 모델

**REQ-IDs:** REQ-003 (토대)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/store/StaticIndex.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/store/IndexManifest.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/store/StaticIndexSerdeTest.java`

**Interfaces:**
- Produces:
  - `record StaticIndex(IndexResult index, WsIndexResult ws, KafkaIndexResult kafka, List<MapperStatement> mappers, List<Set<String>> responseDtoFieldSets, Map<String,List<String>> enumConstants)`
  - `record IndexManifest(int schemaVersion, Map<String,IndexManifest.FileEntry> files)` with `record FileEntry(String root, String hash)`
- Consumes: `io.graphrag.model.Json.mapper()` (Jackson, record 직렬화).

- [ ] **Step 1: 실패 테스트 작성 (직렬화 라운드트립)**

`StaticIndexSerdeTest.java`:
```java
package io.graphrag.builder.store;

import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.KafkaIndexResult;
import io.graphrag.builder.index.WsIndexResult;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StaticIndexSerdeTest {

    @Test
    void staticIndexRoundTrips() throws Exception {
        StaticIndex si = new StaticIndex(
                new IndexResult(List.of(), Map.of(), Set.of(), Map.of()),
                new WsIndexResult(List.of(), Map.of()),
                new KafkaIndexResult(List.of(), Map.of()),
                List.of(), List.of(Set.of("a", "b")), Map.of("p.E", List.of("X", "Y")));

        String json = Json.mapper().writeValueAsString(si);
        StaticIndex back = Json.mapper().readValue(json, StaticIndex.class);

        assertThat(back.enumConstants()).containsEntry("p.E", List.of("X", "Y"));
        assertThat(back.responseDtoFieldSets()).containsExactly(Set.of("a", "b"));
    }

    @Test
    void manifestRoundTrips() throws Exception {
        IndexManifest m = new IndexManifest(1,
                Map.of("a/Foo.java", new IndexManifest.FileEntry("sutSrc", "h1")));
        String json = Json.mapper().writeValueAsString(m);
        IndexManifest back = Json.mapper().readValue(json, IndexManifest.class);
        assertThat(back.schemaVersion()).isEqualTo(1);
        assertThat(back.files().get("a/Foo.java").hash()).isEqualTo("h1");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*StaticIndexSerdeTest'`
Expected: FAIL — `StaticIndex`/`IndexManifest` 없음.

- [ ] **Step 3: 모델 구현**

`StaticIndex.java`:
```java
package io.graphrag.builder.store;

import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.KafkaIndexResult;
import io.graphrag.builder.index.WsIndexResult;
import io.graphrag.model.MapperStatement;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 직렬화 가능한 정적 인덱싱 산출물 전체 묶음(whole-result 캐시 단위). */
public record StaticIndex(
        IndexResult index,
        WsIndexResult ws,
        KafkaIndexResult kafka,
        List<MapperStatement> mappers,
        List<Set<String>> responseDtoFieldSets,
        Map<String, List<String>> enumConstants) {
}
```
`IndexManifest.java`:
```java
package io.graphrag.builder.store;

import java.util.Map;

/** 캐시 매니페스트: 스키마 버전 + 소스 파일 내용 해시. */
public record IndexManifest(int schemaVersion, Map<String, FileEntry> files) {

    /** root = "sutSrc" | "sutResources", hash = 파일 내용 SHA-256. */
    public record FileEntry(String root, String hash) {
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*StaticIndexSerdeTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/store/StaticIndex.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/store/IndexManifest.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/store/StaticIndexSerdeTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(builder): StaticIndex/IndexManifest 직렬화 모델"
```

---

### Task 5: IndexCache — 스캔/판정/로드/저장

**REQ-IDs:** REQ-003, REQ-008, REQ-010

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/store/IndexCache.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/store/IndexCacheTest.java`

**Interfaces:**
- Consumes: `StaticIndex`, `IndexManifest` (Task 4), `io.graphrag.model.Json`.
- Produces:
  - `static final int IndexCache.SCHEMA_VERSION = 1`
  - `static IndexManifest IndexCache.scan(Path sutSrc, Path sutResources)` — 두 루트 .java/.xml 내용 해시.
  - `static boolean IndexCache.isFresh(IndexManifest cached, IndexManifest current)` — schemaVersion 일치 + files 동일.
  - `static Optional<StaticIndex> IndexCache.load(Path cacheDir, IndexManifest current)` — 신선하면 static-index 복원, 아니면/손상이면 empty.
  - `static void IndexCache.save(Path cacheDir, IndexManifest manifest, StaticIndex index)` — atomic write.

- [ ] **Step 1: 실패 테스트 작성**

`IndexCacheTest.java`:
```java
package io.graphrag.builder.store;

import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.KafkaIndexResult;
import io.graphrag.builder.index.WsIndexResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IndexCacheTest {

    private static StaticIndex empty() {
        return new StaticIndex(new IndexResult(List.of(), Map.of(), Set.of(), Map.of()),
                new WsIndexResult(List.of(), Map.of()), new KafkaIndexResult(List.of(), Map.of()),
                List.of(), List.of(), Map.of());
    }

    @Test
    void scanDetectsContentChange() throws Exception {
        Path src = Files.createTempDirectory("src");
        Path res = Files.createTempDirectory("res");
        Files.writeString(src.resolve("A.java"), "class A {}");
        IndexManifest m1 = IndexCache.scan(src, res);
        Files.writeString(src.resolve("A.java"), "class A { int x; }");
        IndexManifest m2 = IndexCache.scan(src, res);
        assertThat(IndexCache.isFresh(m1, m2)).isFalse();
        assertThat(IndexCache.isFresh(m1, m1)).isTrue();
    }

    @Test
    void saveThenLoadRestoresWhenFresh() throws Exception {
        Path cache = Files.createTempDirectory("cache");
        Path src = Files.createTempDirectory("src2");
        Path res = Files.createTempDirectory("res2");
        Files.writeString(src.resolve("A.java"), "class A {}");
        IndexManifest m = IndexCache.scan(src, res);
        IndexCache.save(cache, m, empty());
        assertThat(IndexCache.load(cache, m)).isPresent();
    }

    @Test
    void schemaMismatchTriggersRebuild() throws Exception {       // REQ-008
        Path cache = Files.createTempDirectory("cache3");
        IndexManifest stale = new IndexManifest(IndexCache.SCHEMA_VERSION - 1, Map.of());
        IndexCache.save(cache, stale, empty());
        assertThat(IndexCache.load(cache, new IndexManifest(IndexCache.SCHEMA_VERSION, Map.of())))
                .isEmpty();
    }

    @Test
    void corruptManifestFallsBackToRebuild() throws Exception {   // REQ-010
        Path cache = Files.createTempDirectory("cache4");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("manifest.json"), "{ not json");
        assertThat(IndexCache.load(cache, new IndexManifest(IndexCache.SCHEMA_VERSION, Map.of())))
                .isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*IndexCacheTest'`
Expected: FAIL — `IndexCache` 없음.

- [ ] **Step 3: 구현**

`IndexCache.java`:
```java
package io.graphrag.builder.store;

import io.graphrag.model.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** 정적 인덱싱 whole-result 캐시(manifest.json + static-index.json). */
public final class IndexCache {

    public static final int SCHEMA_VERSION = 1;
    private static final String MANIFEST = "manifest.json";
    private static final String INDEX = "static-index.json";

    private IndexCache() {
    }

    public static IndexManifest scan(Path sutSrc, Path sutResources) {
        Map<String, IndexManifest.FileEntry> files = new LinkedHashMap<>();
        collect(sutSrc, ".java", "sutSrc", files);
        collect(sutResources, ".xml", "sutResources", files);
        return new IndexManifest(SCHEMA_VERSION, files);
    }

    private static void collect(Path root, String ext, String label,
                                Map<String, IndexManifest.FileEntry> out) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(ext)).sorted().forEach(p -> {
                String rel = label + "/" + root.relativize(p).toString().replace('\\', '/');
                out.put(rel, new IndexManifest.FileEntry(label, sha256(p)));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isFresh(IndexManifest cached, IndexManifest current) {
        return cached != null
                && cached.schemaVersion() == current.schemaVersion()
                && cached.files().equals(current.files());
    }

    public static Optional<StaticIndex> load(Path cacheDir, IndexManifest current) {
        Path manifestPath = cacheDir.resolve(MANIFEST);
        Path indexPath = cacheDir.resolve(INDEX);
        if (!Files.exists(manifestPath) || !Files.exists(indexPath)) {
            return Optional.empty();
        }
        try {
            IndexManifest cached = Json.mapper().readValue(manifestPath.toFile(), IndexManifest.class);
            if (!isFresh(cached, current)) {
                return Optional.empty();
            }
            return Optional.of(Json.mapper().readValue(indexPath.toFile(), StaticIndex.class));
        } catch (IOException e) {
            return Optional.empty();   // 손상 → 풀 리빌드 (REQ-010)
        }
    }

    public static void save(Path cacheDir, IndexManifest manifest, StaticIndex index) {
        try {
            Files.createDirectories(cacheDir);
            writeAtomic(cacheDir.resolve(INDEX), Json.mapper().writeValueAsBytes(index));
            writeAtomic(cacheDir.resolve(MANIFEST), Json.mapper().writeValueAsBytes(manifest));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*IndexCacheTest'`
Expected: PASS (4개 테스트 — scan/save-load/schema/corrupt)

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/store/IndexCache.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/store/IndexCacheTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(builder): IndexCache 스캔/신선도판정/로드/원자적저장 + schemaVersion/손상복구"
```

---

### Task 6: BuildConfig.noIncremental + CLI `--no-incremental`

**REQ-IDs:** REQ-007

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (BuildConfig record L129-153 영역 + main() 옵션 파싱)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuildConfigFlagTest.java` (Create)

**Interfaces:**
- Produces: `BuildConfig.noIncremental()` 접근자(record 컴포넌트). `main()`이 `--no-incremental`/`--reindex` → `true`.

- [ ] **Step 1: 실패 테스트 작성**

`BuildConfigFlagTest.java`:
```java
package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class BuildConfigFlagTest {

    @Test
    void buildConfigHasNoIncrementalAccessor() throws Exception {
        Method m = BuilderCli.BuildConfig.class.getMethod("noIncremental");
        assertThat(m.getReturnType()).isEqualTo(boolean.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*BuildConfigFlagTest'`
Expected: FAIL — `noIncremental` 컴포넌트 없음.

- [ ] **Step 3: 구현**

`BuildConfig` record 정의 끝에 `boolean noIncremental` 컴포넌트를 추가하고, `main()`의 `BuildConfig`
생성 인자에 다음을 추가한다(마지막 인자로):
```java
                traceMode(options.get("--trace-mode")),
                options.containsKey("--no-incremental") || options.containsKey("--reindex"));
```
(record 컴포넌트 순서·다른 호출부가 있으면 함께 갱신. `BuildConfig`를 생성하는 다른 위치가 있으면
`false`를 전달한다.)

- [ ] **Step 4: 테스트 통과 + 회귀**

Run: `./gradlew :graph-rag-builder:test --tests '*BuildConfigFlagTest' --tests '*BuilderCli*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuildConfigFlagTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(builder): --no-incremental/--reindex 플래그 + BuildConfig.noIncremental"
```

---

### Task 7: build()에 캐시 흐름 배선 (Stage 2)

**REQ-IDs:** REQ-003, REQ-004, REQ-005, REQ-006, REQ-009

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (Task 3에서 만든 정적 블록 호출부)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/IndexCacheWiringTest.java` (Create)

**Interfaces:**
- Consumes: `IndexCache` (Task 5), `StaticIndex`, Task 3의 `indexStatically(...)`, `BuildConfig.noIncremental()`.
- Produces: `static StaticIndex BuilderCli.staticIndexWithCache(BuildConfig config)` — 캐시 우선, 미스 시 풀 리빌드 + 저장.

- [ ] **Step 1: 실패 테스트 작성 (무변경 0회 / 변경 1회)**

`IndexCacheWiringTest.java`:
```java
package io.graphrag.builder.cli;

import io.graphrag.builder.index.SharedSpoonModel;
import io.graphrag.builder.store.StaticIndex;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexCacheWiringTest {

    private static BuilderCli.BuildConfig cfg(Path src, Path out, boolean noInc) {
        return TestConfigs.minimal(src, out, noInc);   // 헬퍼: 필수 필드만 채운 BuildConfig
    }

    @Test
    void unchangedRebuildUsesCacheZeroBuilds() throws Exception {   // REQ-003
        Path src = Files.createTempDirectory("sut");
        Path out = Files.createTempDirectory("out");
        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/foo\") String f(){return \"x\";} }");

        SharedSpoonModel.resetBuildCount();
        StaticIndex first = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);

        SharedSpoonModel.resetBuildCount();
        StaticIndex second = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        assertThat(SharedSpoonModel.buildCount()).isEqualTo(0);     // 캐시 복원
        assertThat(second.index().endpoints()).hasSameSizeAs(first.index().endpoints());
    }

    @Test
    void changedFileTriggersRebuild() throws Exception {            // REQ-004
        Path src = Files.createTempDirectory("sut2");
        Path out = Files.createTempDirectory("out2");
        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/foo\") String f(){return \"x\";} }");
        BuilderCli.staticIndexWithCache(cfg(src, out, false));

        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/bar\") String f(){return \"x\";} }");
        SharedSpoonModel.resetBuildCount();
        StaticIndex after = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);     // 변경 감지 → 재빌드
        assertThat(after.index().endpoints()).anyMatch(e -> e.path().equals("/bar"));
    }

    @Test
    void noIncrementalForcesRebuild() throws Exception {            // REQ-007 배선 확인
        Path src = Files.createTempDirectory("sut3");
        Path out = Files.createTempDirectory("out3");
        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/foo\") String f(){return \"x\";} }");
        BuilderCli.staticIndexWithCache(cfg(src, out, false));
        SharedSpoonModel.resetBuildCount();
        BuilderCli.staticIndexWithCache(cfg(src, out, true));       // --no-incremental
        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);
    }
}
```
`TestConfigs.minimal(...)`는 `BuildConfig`의 필수 아닌 필드를 기본값으로 채우는 테스트 헬퍼다
(`graph-rag-builder/src/test/java/io/graphrag/builder/cli/TestConfigs.java`로 Create). out·sutSrc만
실제 값, dbConfig 등은 null/기본, sutResources = `sutSrc.resolveSibling("resources")`, noIncremental 인자.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*IndexCacheWiringTest'`
Expected: FAIL — `staticIndexWithCache` 없음.

- [ ] **Step 3: 캐시 흐름 구현**

`BuilderCli`에 추가:
```java
    static StaticIndex staticIndexWithCache(BuildConfig config) {
        Path cacheDir = config.out().resolve("index-cache");
        IndexManifest current = IndexCache.scan(config.sutSrc(), config.sutResources());
        if (!config.noIncremental()) {
            Optional<StaticIndex> hit = IndexCache.load(cacheDir, current);
            if (hit.isPresent()) {
                log.info("static index: cache hit (no source change) — skipping Spoon parse");
                return hit.get();
            }
        }
        StaticIndexBundle b = indexStatically(config.sutSrc(), config.sutResources(), config.authConfig());
        StaticIndex si = new StaticIndex(b.index(), b.ws(), b.kafka(), b.mappers(),
                b.responseDtoFieldSets(), b.enumConstants());
        IndexCache.save(cacheDir, current, si);
        return si;
    }
```
그리고 build()에서 Task 3의 `indexStatically(...)` 직접 호출을 `staticIndexWithCache(config)`로 바꾸고,
반환된 `StaticIndex`에서 변수들을 추출:
```java
        StaticIndex si = staticIndexWithCache(config);
        IndexResult index = si.index();
        io.graphrag.builder.index.WsIndexResult wsIndex = si.ws();
        io.graphrag.builder.index.KafkaIndexResult kafkaIndex = si.kafka();
        List<MapperStatement> mappers = si.mappers();
        List<Set<String>> responseDtoFieldSets = si.responseDtoFieldSets();
        Map<String, List<String>> enumConstants = si.enumConstants();
```
필요한 import: `io.graphrag.builder.store.IndexCache`, `io.graphrag.builder.store.IndexManifest`,
`io.graphrag.builder.store.StaticIndex`, `java.util.Optional`.

- [ ] **Step 4: 테스트 통과 + 회귀**

Run: `./gradlew :graph-rag-builder:test --tests '*IndexCacheWiringTest' --tests '*BuilderCli*' --tests '*BuilderStaticIndexTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/IndexCacheWiringTest.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/TestConfigs.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(builder): build() 정적 인덱싱 whole-result 캐시 배선(무변경 0회/변경 재빌드)"
```

---

### Task 8: E2E 동등성 + 샘플 픽스처

**REQ-IDs:** REQ-002, REQ-003, REQ-004, REQ-005, REQ-006, REQ-007, REQ-009

**Files:**
- Create: `graph-rag-builder/src/test/resources/incremental-sample/` (cross-file 케이스 SUT 소스)
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/IncrementalIndexE2E.java`

**Interfaces:**
- Consumes: `BuilderCli.staticIndexWithCache`, `IndexCache`, `SharedSpoonModel`. (Docker 불요 — 정적
  인덱싱 산출물 `StaticIndex` 동등성만 비교. 전체 `build()`의 graph.json E2E는 기존 BuilderIntegrationTest
  영역이며, 본 E2E는 그 정적 부분의 out-of-process 동등성에 집중.)

> 검증 레벨 주: design spec §7은 "최고 실현가능 out-of-process 레벨"을 요구한다. 전체 graph.json은
> Docker SUT가 필요하므로, 정적 인덱싱 산출물(StaticIndex) 단위의 골든/동등성 비교를 본 기능의 E2E
> 경계로 삼는다(요구사항명세 검증 레벨과 일치).

- [ ] **Step 1: 샘플 픽스처 작성**

`incremental-sample/` 아래에 cross-file 케이스를 포함한 최소 SUT 소스 트리를 만든다:
- `app/Product.java` — `@Entity class Product { @Id Long id; String name; }`
- `app/OrderForm.java` — 폼 커맨드, `Product product;` 필드(REFERENCE 분류 경로)
- `app/OrderController.java` — `@Controller`, 폼 엔드포인트 + `@GetMapping`
- `app/Status.java` — `enum Status { NEW, PAID }`
- `resources/mapper/OrderMapper.xml` — 최소 MyBatis mapper

(정확한 파일 내용은 기존 `src/test/resources`의 샘플 컨트롤러 패턴을 따른다. 핵심은 `@Entity` 참조
폼 필드 + enum + mapper XML이 한 번에 들어가는 것.)

- [ ] **Step 2: 실패 E2E 작성**

`IncrementalIndexE2E.java` — REQ별 `@DisplayName` 부착:
```java
package io.graphrag.builder.cli;

import io.graphrag.builder.index.SharedSpoonModel;
import io.graphrag.builder.store.StaticIndex;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalIndexE2E {

    private static final Path SAMPLE = Path.of("src/test/resources/incremental-sample");

    private static BuilderCli.BuildConfig cfg(Path src, Path out, boolean noInc) {
        return TestConfigs.minimal(src, out, noInc);
    }

    @Test
    @DisplayName("REQ-006: 증분 빌드 산출물 == --no-incremental 풀 리빌드 산출물")
    void incrementalEqualsFullRebuild() throws Exception {
        Path src = copySample();
        Path out = Files.createTempDirectory("out");
        StaticIndex incremental = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        StaticIndex full = BuilderCli.staticIndexWithCache(cfg(src, out, true));
        assertThat(Json.mapper().writeValueAsString(incremental))
                .isEqualTo(Json.mapper().writeValueAsString(full));
    }

    @Test
    @DisplayName("REQ-003: 무변경 재빌드는 Spoon 0회 + 산출물 동일")
    void noChangeRebuildZeroBuilds() throws Exception {
        Path src = copySample();
        Path out = Files.createTempDirectory("out");
        StaticIndex first = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        SharedSpoonModel.resetBuildCount();
        StaticIndex second = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        assertThat(SharedSpoonModel.buildCount()).isEqualTo(0);
        assertThat(Json.mapper().writeValueAsString(second))
                .isEqualTo(Json.mapper().writeValueAsString(first));
    }

    @Test
    @DisplayName("REQ-005: 핸들러 파일 삭제 시 엔드포인트 제거 + 풀 리빌드 동일")
    void deletedFileRemovesEndpoint() throws Exception {
        Path src = copySample();
        Path out = Files.createTempDirectory("out");
        BuilderCli.staticIndexWithCache(cfg(src, out, false));
        Files.delete(src.resolve("app/OrderController.java"));
        StaticIndex after = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        StaticIndex full = BuilderCli.staticIndexWithCache(cfg(src, out, true));
        assertThat(Json.mapper().writeValueAsString(after))
                .isEqualTo(Json.mapper().writeValueAsString(full));
    }

    @Test
    @DisplayName("REQ-009: mapper XML 수정 시 mappers 갱신 + 풀 리빌드 동일")
    void mapperXmlEditUpdatesFragment() throws Exception {
        Path src = copySample();
        Path out = Files.createTempDirectory("out");
        BuilderCli.staticIndexWithCache(cfg(src, out, false));
        Path xml = src.resolveSibling("resources").resolve("mapper/OrderMapper.xml");
        Files.writeString(xml, Files.readString(xml).replace("</mapper>", "<select id='x'/></mapper>"));
        StaticIndex after = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        StaticIndex full = BuilderCli.staticIndexWithCache(cfg(src, out, true));
        assertThat(Json.mapper().writeValueAsString(after))
                .isEqualTo(Json.mapper().writeValueAsString(full));
    }

    private static Path copySample() throws Exception {
        Path dst = Files.createTempDirectory("inc-sample");
        // SAMPLE/app → dst/app, SAMPLE/resources → dst.resolveSibling? 테스트 헬퍼로 src/resources 분리 복사.
        TestConfigs.copyTree(SAMPLE.resolve("app"), dst.resolve("app"));
        TestConfigs.copyTree(SAMPLE.resolve("resources"), dst.resolveSibling("resources"));
        return dst;
    }
}
```
`TestConfigs.copyTree(src, dst)` 헬퍼(디렉터리 재귀 복사)를 Task 7의 `TestConfigs`에 추가한다.
REQ-002(Stage1 동등성)는 Task 3 `indexStatically` 결과가 캐시 미스 풀 리빌드 산출물과 동일함으로
`incrementalEqualsFullRebuild`가 함께 보증한다(공유 모델 == 산출물 정확성).

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*IncrementalIndexE2E'`
Expected: FAIL (픽스처/배선 미완 시) → 구현/픽스처 보정으로 GREEN.

- [ ] **Step 4: GREEN + 매트릭스 갱신**

Run: `./gradlew :graph-rag-builder:test --tests '*IncrementalIndexE2E'`
Expected: PASS. 요구사항명세 매트릭스의 REQ-002~009 행 상태를 🟢로 갱신.

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/test/resources/incremental-sample \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/IncrementalIndexE2E.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/TestConfigs.java \
        docs/superpowers/requirements/2026-06-22-incremental-static-index-requirements.md
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "test(builder): 정적 인덱싱 증분화 E2E(REQ-002~009) + 샘플 픽스처"
```

---

### Task 9: 문서 갱신 + 전체 회귀

**REQ-IDs:** (문서/검증 — 신규 REQ 없음)

**Files:**
- Modify: `graph-rag-builder/README.md` 또는 도구 사용법 문서(존재 시) — `--no-incremental` 옵션,
  `<out>/index-cache/` 캐시, 무변경 재빌드 동작 추가.
- Modify: `BuilderCli` 클래스 javadoc(L53-62 사용법)에 `[--no-incremental]` 추가.

- [ ] **Step 1: 사용법 문서 갱신**

`BuilderCli` 헤더 javadoc 사용법 줄에 `[--no-incremental|--reindex]`를 추가하고, 캐시 위치·무변경
0회 동작을 1~2줄 설명. 별도 도구 README가 있으면 동일 내용 반영.

- [ ] **Step 2: 전체 회귀 실행**

Run: `./gradlew :graph-rag-builder:test`
Expected: PASS (신규 + 기존 전부). 실패 시 해당 Task로 돌아가 수정.

- [ ] **Step 3: 매트릭스 100% 확인**

요구사항명세 매트릭스의 대상(Must 10 + Should 1) 전부 🟢인지, 각 green REQ가 실제 통과 테스트명과
대응하는지 대조. 불일치 시 실제 결과 기준으로 매트릭스 정정.

- [ ] **Step 4: 커밋**

```bash
git add graph-rag-builder/README.md graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        docs/superpowers/requirements/2026-06-22-incremental-static-index-requirements.md
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "docs(builder): 정적 인덱싱 증분 캐시 사용법(--no-incremental) 반영"
```

---

## Self-Review

**1. Spec coverage:**
- G1 단일 모델 → Task 1,3 (REQ-001). G2 무변경 0회 → Task 5,7 (REQ-003). G3 변경 시 정확 재빌드 →
  Task 7,8 (REQ-004/006). G4 동등성 → Task 8 (REQ-006). Stage1 동등성 → Task 8 (REQ-002).
  `--no-incremental` → Task 6 (REQ-007). schemaVersion → Task 5 (REQ-008). XML → Task 8 (REQ-009).
  손상/atomic → Task 5 (REQ-010). 하위호환 → Task 2 (REQ-011). 삭제 → Task 8 (REQ-005). **갭 없음.**
- 비목표(부분모델·탐색단계 Spoon)는 Task에 없음 — 의도된 제외.

**2. Placeholder scan:** 픽스처 파일 내용을 "기존 샘플 패턴 따름"으로 둔 곳(Task 8 Step 1)은 구체
구조(파일명·애노테이션)를 명시했으므로 실행 가능. 그 외 TBD 없음.

**3. Type consistency:** `StaticIndex`(Task 4) 컴포넌트 == `StaticIndexBundle`(Task 3) 구성 ==
`staticIndexWithCache`(Task 7) 변환에서 일치. `IndexCache.scan/isFresh/load/save`(Task 5) 시그니처가
Task 7 호출과 일치. `SharedSpoonModel.build/buildCount/resetBuildCount`(Task 1)가 Task 3/7/8에서
동일 사용. `BuildConfig.noIncremental()`(Task 6)이 Task 7에서 사용.

> 주: Task 3의 `StaticIndexBundle`(cli 패키지 내부 record)과 Task 4의 `StaticIndex`(store 패키지
> 직렬화 record)는 같은 6개 컴포넌트를 갖는 의도적 분리다(cli는 빌드 결과 운반, store는 직렬화 단위).
> Task 7에서 Bundle→StaticIndex 변환. 단순화를 원하면 Task 4에서 Bundle을 StaticIndex로 대체하고
> Task 3을 store.StaticIndex 직접 반환으로 합쳐도 된다(구현자 재량, 동작 동일).
