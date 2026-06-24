# `--sut-src` 멀티 루트 + `--endpoint` glob 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** graph-rag-builder의 `--sut-src`가 여러 명시적 소스 루트(glob/리터럴 혼용)를 받아 합집합만 정확히 파싱하고, `--endpoint`가 glob 패턴(정확 나열과 혼용)을 받도록 한다.

**Architecture:** 새 값 타입 `SourceRoots`(parseRoots + primary)를 도입한다. `--sut-src`는 brace-aware 토큰화 → glob 확장 → 디렉터리 dedup/정렬로 `SourceRoots`를 만든다. 정적 인덱싱은 멀티 루트를 단일 공유 `CtModel`로 파싱하고, 탐색 단계의 per-endpoint 추출기는 `SourceRoots`를 받아 전 루트를 파싱한다. `--endpoint`는 정확→glob 순으로 해석하는 문자열 glob-to-regex 매처를 쓴다. 출처: `docs/superpowers/specs/2026-06-24-sut-src-endpoint-glob-design.md`, 요구사항: `docs/superpowers/requirements/2026-06-24-sut-src-endpoint-glob-requirements.md`.

**Tech Stack:** Java 17, Gradle, Spoon(정적 AST), JUnit 5, Testcontainers, 기존 e2e bash 하니스.

## Global Constraints

- Java 17 (`SharedSpoonModel`은 `setComplianceLevel(17)`).
- 단일 루트 + 정확 셀렉터 경로는 기존 동작 보존(REQ-015): `SourceRoots.single(p)`는 기존 단일-`addInputResource(p)`와 동일 호출로 환원.
- glob 문법은 표준: `*`=세그먼트 내(`/` 미포함), `**`=`/` 횡단, `?`=한 문자, `{a,b}`=택일, `[abc]`=문자 클래스(REQ-009).
- `--endpoint` glob은 **문자열 glob-to-regex** 매처(NIO `PathMatcher` 금지 — 플랫폼 독립, REQ-009). `--sut-src` glob은 파일시스템 NIO `PathMatcher`(디렉터리만 채택).
- 콤마는 **brace 깊이 0에서만** 리스트 구분자(REQ-018). 두 플래그 공통.
- docker compose·SUT·백그라운드를 띄우는 E2E는 모든 종료 경로 teardown + 고유 project/label 자기 것만 정리 + 누수 0(REQ-020). 무차별 `prune`/`pkill -f` 금지.
- REQ-ID는 요구사항명세 기준. 각 task 헤더 아래 `**REQ-IDs:**` 한 줄.
- 빌드/테스트 명령은 워크트리 루트(`.claude/worktrees/feat-multi-sut-endpoint`)에서 실행. 모듈 경로 prefix: `:graph-rag-builder`.

---

## File Structure

신규 파일:
- `graph-rag-builder/src/main/java/io/graphrag/builder/cli/GlobToken.java` — brace-aware 토큰화 유틸 (REQ-018).
- `graph-rag-builder/src/main/java/io/graphrag/builder/cli/GlobMatcher.java` — 문자열 glob→regex 매처 (REQ-009, endpoint).
- `graph-rag-builder/src/main/java/io/graphrag/builder/index/SourceRoots.java` — parseRoots + primary 값 타입.
- `graph-rag-builder/src/main/java/io/graphrag/builder/cli/SutSrcResolver.java` — `--sut-src` 패턴 리스트 → `SourceRoots` (REQ-001/003/004/009/017) + resources 루트 해석(REQ-011/019).
- 테스트: 각 신규 클래스의 `*Test.java`, `MultiRootStaticE2E.java`(정적 레벨 JUnit), `e2e/run-endpoint-glob-e2e.sh`(REQ-005 풀 빌드 — 탐색 vs 정적 비교는 실제 build 필요), 픽스처 소스 트리, e2e 스크립트.

수정 파일:
- `SharedSpoonModel.java` — `build(SourceRoots)` 추가.
- `EndpointSelector.java` — glob 분기.
- `BuildConfig.java` — `sourceRoots` 컴포넌트.
- `IndexCache.java` — `scan(SourceRoots, …)`.
- `MapperXmlIndexer` 호출부 — 멀티 resources.
- `BuilderCli.java` — main() 와이어링, `indexStatically(SourceRoots,…)`, explore() per-endpoint 호출.
- `ConstraintExtractor.java`, `LiteralCandidateExtractor.java`, `ValidationConstraintExtractor.java`, `HandlerSourceExtractor.java`, `InputOracle.java` — `SourceRoots` 오버로드.
- `docs/03-graph-rag-builder.md`, CLI usage 문자열.

---

## Task 1: brace-aware 토큰화 유틸 `GlobToken`

**REQ-IDs:** REQ-018

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/GlobToken.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/GlobTokenTest.java`

**Interfaces:**
- Produces: `static List<String> split(String csv)` — brace 깊이 0의 콤마에서만 분리, 각 토큰 strip, 빈 토큰 제외.

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobTokenTest {
    @Test
    void bracePreservedCommaSplit() {
        assertEquals(List.of("a/b/c/{e,common}"), GlobToken.split("a/b/c/{e,common}"));
    }

    @Test
    void plainCommaSplitsWithStrip() {
        assertEquals(List.of("a", "b"), GlobToken.split("a, b"));
    }

    @Test
    void mixedBraceAndList() {
        assertEquals(List.of("a/{x,y}", "b/**"), GlobToken.split("a/{x,y}, b/**"));
    }

    @Test
    void blanksDropped() {
        assertEquals(List.of("a", "b"), GlobToken.split("a, , b,"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.GlobTokenTest'`
Expected: FAIL (`GlobToken` 미존재 — 컴파일 에러).

- [ ] **Step 3: 최소 구현**

```java
package io.graphrag.builder.cli;

import java.util.ArrayList;
import java.util.List;

/** brace-aware CSV 토큰화: brace 깊이 0의 콤마에서만 분리(`{a,b}` 보존). REQ-018. */
public final class GlobToken {

    private GlobToken() {}

    public static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '{') {
                depth++;
                cur.append(c);
            } else if (c == '}') {
                if (depth > 0) { depth--; }
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                addStripped(out, cur);
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        addStripped(out, cur);
        return out;
    }

    private static void addStripped(List<String> out, StringBuilder sb) {
        String t = sb.toString().strip();
        if (!t.isEmpty()) {
            out.add(t);
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.GlobTokenTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/GlobToken.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/GlobTokenTest.java
git commit -m "feat(builder): brace-aware CSV 토큰화 GlobToken (REQ-018)"
```

---

## Task 2: 문자열 glob 매처 `GlobMatcher`

**REQ-IDs:** REQ-009 (endpoint side)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/GlobMatcher.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/GlobMatcherTest.java`

**Interfaces:**
- Produces:
  - `static boolean hasGlobMeta(String spec)` — `* ? { [` 중 하나라도 포함.
  - `static boolean matches(String glob, String text)` — glob→regex 변환 후 전체 매칭. 형식 오류 시 `IllegalArgumentException`(원시 `PatternSyntaxException` 감쌈).

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobMatcherTest {
    @Test
    void starVsDoubleStar() {
        assertTrue(GlobMatcher.matches("POST /api/orders/*", "POST /api/orders/x"));
        assertFalse(GlobMatcher.matches("POST /api/orders/*", "POST /api/orders/x/y")); // * 는 / 미횡단
        assertTrue(GlobMatcher.matches("POST /api/orders/**", "POST /api/orders/x/y"));
    }

    @Test
    void idGlobAndQuestionAndBrace() {
        assertTrue(GlobMatcher.matches("post-api-orders-*", "post-api-orders-batch"));
        assertTrue(GlobMatcher.matches("GET /api/{users,orders}/**", "GET /api/users/1"));
        assertTrue(GlobMatcher.matches("get-api-?", "get-api-x"));
    }

    @Test
    void pathStringPortableNoPathOf() {
        // "/"-경로 문자열에 대해 예외 없이 동작(Path.of 미사용 검증 — 매칭만 확인)
        assertTrue(GlobMatcher.matches("*/api/**", "GET /api/x"));
    }

    @Test
    void hasGlobMetaDetectsMetachars() {
        assertTrue(GlobMatcher.hasGlobMeta("a/*"));
        assertTrue(GlobMatcher.hasGlobMeta("a?"));
        assertTrue(GlobMatcher.hasGlobMeta("{a,b}"));
        assertFalse(GlobMatcher.hasGlobMeta("POST /api/orders"));
        assertFalse(GlobMatcher.hasGlobMeta("post-api-orders"));
    }

    @Test
    void malformedGlobWrapped() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GlobMatcher.matches("a/[", "a/x"));
        assertFalse(ex.getMessage().isBlank());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.GlobMatcherTest'`
Expected: FAIL (미존재).

- [ ] **Step 3: 최소 구현**

```java
package io.graphrag.builder.cli;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 문자열 glob→regex 매처(플랫폼 독립). NIO PathMatcher 미사용 — 대상이 파일시스템 경로가
 * 아니라 endpoint id / "METHOD /path" 문자열이고 OS 구분자에 무관해야 하기 때문. REQ-009.
 * 문법: *=세그먼트 내([^/]*), **=/ 횡단(.*), ?=[^/], {a,b}=(a|b), [abc]=문자클래스.
 */
public final class GlobMatcher {

    private GlobMatcher() {}

    public static boolean hasGlobMeta(String spec) {
        return spec.indexOf('*') >= 0 || spec.indexOf('?') >= 0
                || spec.indexOf('{') >= 0 || spec.indexOf('[') >= 0;
    }

    public static boolean matches(String glob, String text) {
        Pattern p = compile(glob);
        return p.matcher(text).matches();
    }

    private static Pattern compile(String glob) {
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        re.append(".*");
                        i++;
                    } else {
                        re.append("[^/]*");
                    }
                    break;
                case '?':
                    re.append("[^/]");
                    break;
                case '{':
                    re.append('(');
                    break;
                case '}':
                    re.append(')');
                    break;
                case ',':
                    re.append('|');
                    break;
                case '[':
                    re.append('[');   // 문자 클래스 시작 — 그대로 전달
                    break;
                case ']':
                    re.append(']');
                    break;
                // 정규식 특수문자 이스케이프
                case '.': case '(': case ')': case '+': case '^':
                case '$': case '|': case '\\':
                    re.append('\\').append(c);
                    break;
                default:
                    re.append(c);
            }
            i++;
        }
        re.append('$');
        try {
            return Pattern.compile(re.toString());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "malformed glob '" + glob + "': " + e.getDescription(), e);
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.GlobMatcherTest'`
Expected: PASS (5 tests).

> 참고: `{` 가 정규식 그룹 `(`로, `,`가 `|`로 변환되므로 brace 그룹 안의 `,`는 택일이 된다. brace 밖의 `,`는 endpoint 셀렉터에 등장하지 않는다(이미 GlobToken.split으로 분리됨).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/GlobMatcher.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/GlobMatcherTest.java
git commit -m "feat(builder): 문자열 glob→regex 매처 GlobMatcher (REQ-009)"
```

---

## Task 3: `SourceRoots` 값 타입

**REQ-IDs:** REQ-001 (기반), REQ-015 (단일 환원)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/SourceRoots.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/SourceRootsTest.java`

**Interfaces:**
- Produces:
  - `record SourceRoots(List<Path> parseRoots, Path primary)`
  - `static SourceRoots single(Path dir)` → `parseRoots=[dir]`, `primary=dir`.
  - `static SourceRoots of(List<Path> roots, Path primary)` — roots 비었으면 `IllegalArgumentException`.
  - `boolean isMulti()` → `parseRoots.size() > 1`.

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceRootsTest {
    @Test
    void singleReducesToOneRoot() {
        SourceRoots r = SourceRoots.single(Path.of("/a/b"));
        assertEquals(List.of(Path.of("/a/b")), r.parseRoots());
        assertEquals(Path.of("/a/b"), r.primary());
        assertFalse(r.isMulti());
    }

    @Test
    void ofMultiFlagsMulti() {
        SourceRoots r = SourceRoots.of(List.of(Path.of("/a"), Path.of("/b")), Path.of("/a"));
        assertTrue(r.isMulti());
        assertEquals(Path.of("/a"), r.primary());
    }

    @Test
    void emptyRootsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceRoots.of(List.of(), Path.of("/a")));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.index.SourceRootsTest'`
Expected: FAIL (미존재).

- [ ] **Step 3: 최소 구현**

```java
package io.graphrag.builder.index;

import java.nio.file.Path;
import java.util.List;

/** Spoon 파싱 루트 합집합(parseRoots) + 경로 파생용 단일 대표(primary). */
public record SourceRoots(List<Path> parseRoots, Path primary) {

    public SourceRoots {
        if (parseRoots == null || parseRoots.isEmpty()) {
            throw new IllegalArgumentException("parseRoots must be non-empty");
        }
        parseRoots = List.copyOf(parseRoots);
    }

    public static SourceRoots single(Path dir) {
        return new SourceRoots(List.of(dir), dir);
    }

    public static SourceRoots of(List<Path> roots, Path primary) {
        return new SourceRoots(roots, primary);
    }

    public boolean isMulti() {
        return parseRoots.size() > 1;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.index.SourceRootsTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/SourceRoots.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/SourceRootsTest.java
git commit -m "feat(builder): SourceRoots 값 타입 (parseRoots + primary)"
```

---

## Task 4: `--sut-src` 패턴 해석기 `SutSrcResolver`

**REQ-IDs:** REQ-001, REQ-003, REQ-004, REQ-009 (sut-src), REQ-011 (resources), REQ-017

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/SutSrcResolver.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/SutSrcResolverTest.java`

**Interfaces:**
- Consumes: `GlobToken.split`(Task 1), `SourceRoots`(Task 3).
- Produces:
  - `static SourceRoots resolve(String sutSrcArg, Path resourcesArg)` — brace-aware 토큰화 → 패턴별 glob 확장(디렉터리만) → dedup(canonical)+정렬 → `SourceRoots`. 어느 패턴이든 0매칭이면 그 패턴 지목 `IllegalArgumentException`. primary = **정렬된 첫 루트(항상)** — `resourcesArg`는 primary에 영향 없음(resources 스캔 전용).
  - `static List<Path> resourceDirs(SourceRoots roots, Path resourcesArg)` — `resourcesArg`(있으면 그 1개) 또는 전 parseRoots의 `resolveSibling("resources")` 중 존재하는 디렉터리. REQ-011/019.

- [ ] **Step 1: 실패 테스트 작성** (`@TempDir` 사용)

```java
package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SutSrcResolverTest {

    private Path mkdirs(Path base, String rel) throws Exception {
        Path p = base.resolve(rel);
        Files.createDirectories(p);
        return p;
    }

    @Test
    void braceSelectsSiblingsExcludesOthers(@TempDir Path tmp) throws Exception {
        Path feature = mkdirs(tmp, "a/b/c/feature");
        Path common = mkdirs(tmp, "a/b/c/common");
        mkdirs(tmp, "a/b/c/other");
        SourceRoots r = SutSrcResolver.resolve(tmp + "/a/b/c/{feature,common}", null);
        assertEquals(List.of(common.toRealPath(), feature.toRealPath()),
                r.parseRoots().stream().sorted().toList());
    }

    @Test
    void mixLiteralAndGlob(@TempDir Path tmp) throws Exception {
        Path orders = mkdirs(tmp, "a/orders");
        Path commonSub = mkdirs(tmp, "a/common/sub");
        SourceRoots r = SutSrcResolver.resolve(tmp + "/a/orders, " + tmp + "/a/common/**", null);
        assertTrue(r.parseRoots().contains(orders.toRealPath()));
        assertTrue(r.parseRoots().contains(commonSub.toRealPath()));
    }

    @Test
    void zeroMatchFailsFastNamingPattern(@TempDir Path tmp) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SutSrcResolver.resolve(tmp + "/ghost/**", null));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void dedupAndStableOrder(@TempDir Path tmp) throws Exception {
        Path feature = mkdirs(tmp, "a/feature");
        // 같은 디렉터리를 두 패턴이 매칭
        SourceRoots r = SutSrcResolver.resolve(tmp + "/a/feature, " + tmp + "/a/*", null);
        long featureCount = r.parseRoots().stream().filter(p -> p.equals(feature.toRealPath())).count();
        assertEquals(1, featureCount);
    }

    @Test
    void starDoesNotRecurseDoubleStarDoes(@TempDir Path tmp) throws Exception {
        mkdirs(tmp, "a/x");
        Path deep = mkdirs(tmp, "a/x/deep");
        SourceRoots shallow = SutSrcResolver.resolve(tmp + "/a/*", null);
        assertFalse(shallow.parseRoots().contains(deep.toRealPath()));
        SourceRoots recursive = SutSrcResolver.resolve(tmp + "/a/**", null);
        assertTrue(recursive.parseRoots().contains(deep.toRealPath()));
    }

    @Test
    void malformedGlobWrapped(@TempDir Path tmp) throws Exception {
        mkdirs(tmp, "a");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SutSrcResolver.resolve(tmp + "/a/[", null));   // 불균형 bracket
        assertTrue(ex.getMessage().contains("malformed") || ex.getMessage().contains("["));
    }

    @Test
    void resourceDirsFallbackPerRoot(@TempDir Path tmp) throws Exception {
        Path featureSrc = mkdirs(tmp, "feature/src/main/java");
        Path featureRes = mkdirs(tmp, "feature/src/main/resources");
        SourceRoots r = SourceRoots.single(featureSrc);
        assertEquals(List.of(featureRes), SutSrcResolver.resourceDirs(r, null));
    }

    @Test
    void resourceDirsScansAllRootsWhenNotExplicit(@TempDir Path tmp) throws Exception {
        Path fSrc = mkdirs(tmp, "feature/src/main/java");
        Path fRes = mkdirs(tmp, "feature/src/main/resources");
        Path cSrc = mkdirs(tmp, "common/src/main/java");
        Path cRes = mkdirs(tmp, "common/src/main/resources");
        SourceRoots r = SourceRoots.of(List.of(fSrc, cSrc), fSrc);
        assertEquals(List.of(fRes, cRes), SutSrcResolver.resourceDirs(r, null));  // REQ-019
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.SutSrcResolverTest'`
Expected: FAIL (미존재).

- [ ] **Step 3: 최소 구현**

```java
package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** --sut-src 패턴 리스트(브레이스/리터럴/glob 혼용) → SourceRoots. REQ-001/003/004/009/017. */
public final class SutSrcResolver {

    private SutSrcResolver() {}

    public static SourceRoots resolve(String sutSrcArg, Path resourcesArg) {
        List<String> patterns = GlobToken.split(sutSrcArg);
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("--sut-src had no non-blank pattern");
        }
        Set<Path> roots = new LinkedHashSet<>();
        for (String pattern : patterns) {
            List<Path> matched = expand(pattern);
            if (matched.isEmpty()) {
                throw new IllegalArgumentException(
                        "--sut-src '" + pattern + "' matched no source directory");
            }
            roots.addAll(matched);
        }
        List<Path> sorted = new ArrayList<>(roots);
        sorted.sort(Comparator.naturalOrder());   // canonical 경로 안정 정렬(REQ-017)
        // primary 는 항상 첫 parse root(정렬 후) — 경로 파생/로그/단일 루트 sutSrc 환원용.
        // resourcesArg.getParent()를 쓰면 src/main 등 java 루트가 아닌 곳을 가리켜 위험(Gemini I3).
        return SourceRoots.of(sorted, sorted.get(0));
    }

    /** 패턴 1개 → 매칭 디렉터리(canonical). glob 메타 없으면 그 경로 자체(존재·디렉터리일 때). */
    private static List<Path> expand(String pattern) {
        if (!GlobMatcher.hasGlobMeta(pattern)) {
            Path p = Path.of(pattern);
            return Files.isDirectory(p) ? List.of(canonical(p)) : List.of();
        }
        // 절대·forward-slash 패턴으로 정규화 — NIO glob 은 항상 '/' 구분자를 쓰고,
        // Path.of(pattern).toString()은 Windows에서 '\'로 바꿔 glob을 깨뜨린다(Gemini I4).
        String absPattern = toAbsoluteGlob(pattern);
        Path base = globBase(absPattern);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + absPattern);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--sut-src malformed glob '" + pattern + "': " + e.getMessage(), e);
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isDirectory)
                // 매칭은 절대경로 문자열(forward-slash)로 — walk 결과를 절대화해 './x' 불일치 방지(Gemini I5)
                .filter(p -> matcher.matches(Path.of(p.toAbsolutePath().normalize().toString())))
                .forEach(p -> out.add(canonical(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 상대 패턴을 CWD 기준 절대 forward-slash glob 으로. 이미 절대면 그대로. */
    private static String toAbsoluteGlob(String pattern) {
        String fwd = pattern.replace('\\', '/');
        if (fwd.startsWith("/")) {
            return fwd;
        }
        String cwd = Path.of("").toAbsolutePath().toString().replace('\\', '/');
        return cwd + "/" + fwd;
    }

    /** 첫 glob 메타문자 이전의 최장 비-glob 접두 디렉터리(절대 패턴 기준). */
    private static Path globBase(String absPattern) {
        int meta = absPattern.length();
        for (int i = 0; i < absPattern.length(); i++) {
            char c = absPattern.charAt(i);
            if (c == '*' || c == '?' || c == '{' || c == '[') { meta = i; break; }
        }
        int slash = absPattern.lastIndexOf('/', meta);
        String basePart = slash > 0 ? absPattern.substring(0, slash) : "/";
        return Path.of(basePart);
    }

    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** resources 스캔 대상: --sut-resources 명시(있으면 1개) 또는 전 루트 sibling resources 중 존재분. REQ-011/019. */
    public static List<Path> resourceDirs(SourceRoots roots, Path resourcesArg) {
        if (resourcesArg != null) {
            return List.of(resourcesArg);
        }
        List<Path> out = new ArrayList<>();
        for (Path root : roots.parseRoots()) {
            Path res = root.resolveSibling("resources");
            if (Files.isDirectory(res)) {
                out.add(res);
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.SutSrcResolverTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/SutSrcResolver.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/SutSrcResolverTest.java
git commit -m "feat(builder): --sut-src 멀티 루트 해석기 SutSrcResolver (REQ-001/003/004/009/017)"
```

---

## Task 5: `SharedSpoonModel.build(SourceRoots)`

**REQ-IDs:** REQ-001 (기반), REQ-015

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/SharedSpoonModel.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/SharedSpoonModelMultiRootTest.java`

**Interfaces:**
- Produces: `static CtModel build(SourceRoots roots)` — 각 parseRoot를 `addInputResource`. 기존 `build(Path)`는 `build(SourceRoots.single(p))`로 위임.

- [ ] **Step 1: 실패 테스트 작성** (두 루트의 클래스가 모두 모델에 존재)

```java
package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.CtModel;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SharedSpoonModelMultiRootTest {
    @Test
    void buildsFromAllRoots(@TempDir Path tmp) throws Exception {
        Path ra = Files.createDirectories(tmp.resolve("ra/p"));
        Path rb = Files.createDirectories(tmp.resolve("rb/q"));
        Files.writeString(ra.resolve("A.java"), "package p; public class A {}");
        Files.writeString(rb.resolve("B.java"), "package q; public class B {}");
        CtModel model = SharedSpoonModel.build(SourceRoots.of(List.of(tmp.resolve("ra"), tmp.resolve("rb")), tmp.resolve("ra")));
        List<String> types = model.getAllTypes().stream().map(t -> t.getQualifiedName()).toList();
        assertTrue(types.contains("p.A"));
        assertTrue(types.contains("q.B"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.index.SharedSpoonModelMultiRootTest'`
Expected: FAIL (`build(SourceRoots)` 미존재).

- [ ] **Step 3: 구현** — `SharedSpoonModel.java`의 `build(Path srcDir)` 본문을 다음으로 교체/추가:

```java
    public static CtModel build(Path srcDir) {
        return build(SourceRoots.single(srcDir));
    }

    public static CtModel build(SourceRoots roots) {
        Launcher launcher = new Launcher();
        for (Path root : roots.parseRoots()) {
            launcher.addInputResource(root.toString());
        }
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();
        BUILD_COUNT.incrementAndGet();
        return model;
    }
```

(import 추가: 이미 같은 패키지의 `SourceRoots` — import 불요.)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.index.SharedSpoonModelMultiRootTest'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/SharedSpoonModel.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/SharedSpoonModelMultiRootTest.java
git commit -m "feat(builder): SharedSpoonModel.build(SourceRoots) 멀티 루트 파싱"
```

---

## Task 6: `EndpointSelector` glob 분기

**REQ-IDs:** REQ-005 (매칭), REQ-006, REQ-007, REQ-008

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/EndpointSelector.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/EndpointSelectorGlobTest.java`

**Interfaces:**
- Consumes: `GlobMatcher`(Task 2).
- 기존 `resolve(List<String> specs, List<Endpoint>, List<WsEndpoint>, List<KafkaConsumer>)` 시그니처 유지(REQ-007 하위호환).

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.cli;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EndpointSelectorGlobTest {

    // 실제 io.graphrag.model.Endpoint record 시그니처에 맞춤:
    // (id, httpMethod, path, handlerClass, handlerMethod, params, authRequired). 구현 시 소스로 최종 확인.
    private Endpoint ep(String id, String method, String path) {
        return new Endpoint(id, method, path, "C", "m", List.of(), false);
    }

    private final List<Endpoint> eps = List.of(
            ep("post-api-orders", "POST", "/api/orders"),
            ep("post-api-orders-batch", "POST", "/api/orders/batch"),
            ep("get-api-users-id", "GET", "/api/users/{id}"));

    @Test
    void globMatchesMethodPath() {
        Set<String> r = EndpointSelector.resolve(List.of("POST /api/orders/**"), eps, List.of(), List.of());
        assertEquals(Set.of("post-api-orders-batch"), r);  // /api/orders 자체는 /** 횡단 미포함
    }

    @Test
    void globMatchesId() {
        Set<String> r = EndpointSelector.resolve(List.of("post-api-orders-*"), eps, List.of(), List.of());
        assertEquals(Set.of("post-api-orders-batch"), r);
    }

    @Test
    void mixExactAndGlob() {
        Set<String> r = EndpointSelector.resolve(
                List.of("post-api-orders", "GET /api/users/**"), eps, List.of(), List.of());
        assertEquals(Set.of("post-api-orders", "get-api-users-id"), r);
    }

    @Test
    void exactBackwardCompat() {
        Set<String> r = EndpointSelector.resolve(List.of("POST /api/orders"), eps, List.of(), List.of());
        assertEquals(Set.of("post-api-orders"), r);
    }

    @Test
    void globZeroMatchFails() {
        assertThrows(IllegalArgumentException.class,
                () -> EndpointSelector.resolve(List.of("DELETE /nope/**"), eps, List.of(), List.of()));
    }
}
```

> 주의: `Endpoint` 생성자 시그니처는 실제 `io.graphrag.model.Endpoint`에 맞춰 조정한다(구현 시 `shared-model`의 record 정의를 확인해 인자 채움). 위는 형태 예시.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.EndpointSelectorGlobTest'`
Expected: FAIL.

- [ ] **Step 3: 구현** — `EndpointSelector.resolve`의 for 루프에서 정확 매칭 실패 후 glob 분기를 추가. 기존 throw 직전을 다음으로 교체:

```java
            String byMethodPath = matchMethodPath(spec, endpoints);
            if (byMethodPath != null) { resolved.add(byMethodPath); continue; }
            if (GlobMatcher.hasGlobMeta(spec)) {
                List<String> globHits = matchGlob(spec, endpoints, wsEndpoints, kafkaConsumers);
                if (!globHits.isEmpty()) { resolved.addAll(globHits); continue; }
            }
            throw new IllegalArgumentException(
                    "no explorable unit matches --endpoint '" + spec + "'. candidates: "
                            + candidates(endpoints, wsEndpoints, kafkaConsumers));
```

그리고 새 private 메서드 추가:

```java
    /** glob 셀렉터 → id 또는 "METHOD /path" 매칭 단위 id들(순서 보존). */
    private static List<String> matchGlob(String spec, List<Endpoint> endpoints,
            List<WsEndpoint> wsEndpoints, List<KafkaConsumer> kafkaConsumers) {
        List<String> hits = new ArrayList<>();
        // method 토큰만 대문자화(httpMethod는 EndpointIndexer가 대문자로 저장). path 는 case 보존
        // — spec 전체를 toUpperCase 하면 "/API/ORDERS"가 되어 소문자 path와 영구 미스(critical).
        String specMethodUpper = upperFirstToken(spec);
        for (Endpoint e : endpoints) {
            String methodPath = e.httpMethod().toUpperCase() + " " + e.path();
            if (GlobMatcher.matches(spec, e.id())
                    || GlobMatcher.matches(specMethodUpper, methodPath)) {
                hits.add(e.id());
            }
        }
        for (WsEndpoint w : wsEndpoints) {
            if (GlobMatcher.matches(spec, w.id())) { hits.add(w.id()); }
        }
        for (KafkaConsumer k : kafkaConsumers) {
            if (GlobMatcher.matches(spec, k.id())) { hits.add(k.id()); }
        }
        return hits;
    }

    /** spec 의 첫 공백 이전(=HTTP method 토큰)만 대문자화. 공백 없으면(=id glob) 원본 그대로. */
    private static String upperFirstToken(String spec) {
        int sp = spec.indexOf(' ');
        if (sp <= 0) { return spec; }
        return spec.substring(0, sp).toUpperCase() + spec.substring(sp);
    }
```

(import: `io.graphrag.builder.cli.GlobMatcher`는 동일 패키지 — 불요. `java.util.ArrayList` 이미 import됨.)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.EndpointSelectorGlobTest'`
Expected: PASS (5 tests). 기존 `EndpointSelector` 테스트도 회귀 확인:
Run: `./gradlew :graph-rag-builder:test --tests '*EndpointSelector*'`

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/EndpointSelector.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/EndpointSelectorGlobTest.java
git commit -m "feat(builder): --endpoint glob 매칭(id+METHOD path), 정확/혼용 보존 (REQ-005/006/007/008)"
```

---

## Task 7: `BuildConfig`에 `sourceRoots` 컴포넌트 추가

**REQ-IDs:** REQ-001 (기반), REQ-015

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuildConfigSourceRootsTest.java`

**Interfaces:**
- `sourceRoots`를 canonical record의 **마지막(26번째) 컴포넌트**로 추가(현 canonical은 25-arg → 26-arg). compact 생성자에서 `null → SourceRoots.single(sutSrc)` 정규화. 기존 편의 생성자 **5개**는 `this(...)` 끝에 `null`을 추가(정규화에 위임) → **외부 호출 시그니처 불변**(REQ-015).

> 설계 §6.4는 "2번째 위치"를 제안했으나, 마지막 위치 + compact 정규화가 위치 기반 호출부에 영향이 없어 더 안전하다. 이 편차는 design spec과 동기화한다(Task 17에서 한 줄 반영).

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BuildConfigSourceRootsTest {
    @Test
    void defaultsToSingleFromSutSrc() {
        BuildConfig c = new BuildConfig(Path.of("/a/src"), Path.of("/a/res"), Path.of("/a.jar"),
                Path.of("/out"), "sut", "sha", null, 60, null, null, java.util.Map.of());
        assertEquals(SourceRoots.single(Path.of("/a/src")), c.sourceRoots());
        assertEquals(Path.of("/a/src"), c.sourceRoots().primary());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.BuildConfigSourceRootsTest'`
Expected: FAIL (`sourceRoots()` 미존재).

- [ ] **Step 3: 구현**
  1. import 추가: `import io.graphrag.builder.index.SourceRoots;`
  2. canonical record 헤더 끝에 컴포넌트 추가: `LlmOptions llm` 다음에 `, SourceRoots sourceRoots` (26번째, 마지막).
  3. compact 생성자에 정규화 한 줄 추가:
     ```java
         sourceRoots = sourceRoots == null ? SourceRoots.single(sutSrc) : sourceRoots;
     ```
  4. **편의 생성자 5개 각각**의 `this(...)` 호출 끝(현재 마지막 인자 `LlmOptions.disabled()` 뒤)에 `, null`을 추가한다. 생성자는 arg-수로 식별(라인 번호는 컴포넌트 추가 후 이동하므로 사용 금지):
     - 23-arg 편의(reflectInstantiate·llm 생략): `...noIncremental, true, LlmOptions.disabled())` → `..., LlmOptions.disabled(), null)`
     - 24-arg 편의(llm 생략): `...reflectInstantiate, LlmOptions.disabled())` → `..., LlmOptions.disabled(), null)`
     - 11-arg 풀빌드(증분 없음): `...false, true, LlmOptions.disabled())` → `..., LlmOptions.disabled(), null)`
     - 17-arg 편의(attach/requestHeaders 생략): `...false, true, LlmOptions.disabled())` → `..., LlmOptions.disabled(), null)`
     - 21-arg 편의(classifierConfig/noIncremental 생략): `...false, true, LlmOptions.disabled())` → `..., LlmOptions.disabled(), null)`

> 모든 편의 생성자는 canonical로 위임하며 `sourceRoots`에 `null`을 넘기고, compact 생성자가 `SourceRoots.single(sutSrc)`로 정규화한다. canonical 26-arg를 직접 호출하는 곳은 `BuilderCli.main()`뿐이며 Task 11에서 `sourceRoots`를 명시 전달한다.

- [ ] **Step 4: 통과 확인 + 전체 컴파일**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.BuildConfigSourceRootsTest'`
Run: `./gradlew :graph-rag-builder:compileJava :graph-rag-builder:compileTestJava`
Expected: PASS + 컴파일 성공(모든 기존 `new BuildConfig(...)` 호출부는 편의 생성자라 무변경).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuildConfigSourceRootsTest.java
git commit -m "feat(builder): BuildConfig.sourceRoots 컴포넌트(기본 single 환원, 하위호환)"
```

---

## Task 8: `IndexCache.scan(SourceRoots, …)` 멀티 루트 지문

**REQ-IDs:** REQ-013

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/store/IndexCache.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/store/IndexCacheMultiRootTest.java`

**Interfaces:**
- Consumes: `SourceRoots`(Task 3), `SutSrcResolver.resourceDirs`(Task 4).
- Produces: `static IndexManifest scan(SourceRoots roots, List<Path> resourceDirs, AuthConfig authConfig)` — 각 parseRoot의 `.java`를 루트-인덱스 prefix 라벨로 수집, 각 resourceDir의 `.xml` 수집. 기존 `scan(Path,…)` 유지.

- [ ] **Step 1: 실패 테스트 작성** — 비-primary 루트 변경 시 manifest가 달라짐:

```java
package io.graphrag.builder.store;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IndexCacheMultiRootTest {
    @Test
    void nonPrimaryJavaChangeChangesManifest(@TempDir Path tmp) throws Exception {
        Path primary = Files.createDirectories(tmp.resolve("feature"));
        Path nonPrimary = Files.createDirectories(tmp.resolve("common"));
        Files.writeString(primary.resolve("F.java"), "package f; class F {}");
        Files.writeString(nonPrimary.resolve("C.java"), "package c; class C {}");
        SourceRoots roots = SourceRoots.of(List.of(primary, nonPrimary), primary);

        IndexManifest m1 = IndexCache.scan(roots, List.of(), null);
        Files.writeString(nonPrimary.resolve("C.java"), "package c; class C { int x; }");
        IndexManifest m2 = IndexCache.scan(roots, List.of(), null);

        assertFalse(IndexCache.isFresh(m1, m2));   // 비-primary .java 변경 → 캐시 miss (isFresh=false)
    }

    @Test
    void nonPrimaryMapperXmlChangeChangesManifest(@TempDir Path tmp) throws Exception {
        Path primary = Files.createDirectories(tmp.resolve("feature/java"));
        Path npRes = Files.createDirectories(tmp.resolve("common/resources"));
        Files.writeString(primary.resolve("F.java"), "package f; class F {}");
        Files.writeString(npRes.resolve("CommonMapper.xml"), "<mapper><select id=\"a\"/></mapper>");
        SourceRoots roots = SourceRoots.of(List.of(primary), primary);

        IndexManifest m1 = IndexCache.scan(roots, List.of(npRes), null);
        Files.writeString(npRes.resolve("CommonMapper.xml"), "<mapper><select id=\"a\"/><select id=\"b\"/></mapper>");
        IndexManifest m2 = IndexCache.scan(roots, List.of(npRes), null);

        assertFalse(IndexCache.isFresh(m1, m2));   // 비-primary mapper XML 변경 → 캐시 miss (REQ-013)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.store.IndexCacheMultiRootTest'`
Expected: FAIL (오버로드 미존재).

- [ ] **Step 3: 구현** — `IndexCache.java`에 오버로드 추가(기존 `scan(Path,…)` 유지):

```java
    public static IndexManifest scan(SourceRoots roots, List<Path> resourceDirs, AuthConfig authConfig) {
        Map<String, IndexManifest.FileEntry> files = new LinkedHashMap<>();
        List<Path> parseRoots = roots.parseRoots();
        for (int i = 0; i < parseRoots.size(); i++) {
            collect(parseRoots.get(i), ".java", "sutSrc#" + i, files);
        }
        for (int i = 0; i < resourceDirs.size(); i++) {
            collect(resourceDirs.get(i), ".xml", "sutResources#" + i, files);
        }
        return new IndexManifest(SCHEMA_VERSION, buildAuthFingerprint(authConfig), files);
    }
```

(import 추가: `import io.graphrag.builder.index.SourceRoots;`)

> 루트-인덱스 prefix(`sutSrc#0`, `sutSrc#1`)로 라벨링해 서로 다른 루트의 동일 상대경로 파일이 충돌하지 않고, 어느 루트가 바뀌어도 manifest가 달라진다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.store.IndexCacheMultiRootTest'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/store/IndexCache.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/store/IndexCacheMultiRootTest.java
git commit -m "feat(builder): IndexCache.scan(SourceRoots) 멀티 루트 지문 (REQ-013)"
```

---

## Task 9: 정적 인덱싱을 `SourceRoots`로 — `indexStatically` + `staticIndexWithCache` + MapperXml 멀티 resources

**REQ-IDs:** REQ-001, REQ-002, REQ-011, REQ-019, REQ-013

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (330–376행 영역)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/MultiRootStaticIndexTest.java`

**Interfaces:**
- Consumes: `SharedSpoonModel.build(SourceRoots)`(Task 5), `SutSrcResolver.resourceDirs`(Task 4), `IndexCache.scan(SourceRoots,…)`(Task 8), `BuildConfig.sourceRoots()`(Task 7).
- Produces: `static StaticIndexBundle indexStatically(SourceRoots roots, List<Path> resourceDirs, AuthConfig authConfig)`. 기존 `indexStatically(Path,…)`는 `SourceRoots.single` + `resolveSibling("resources")` 1개로 위임.

- [ ] **Step 1: 실패 테스트 작성** — 두 루트의 컨트롤러가 모두, 제외 형제는 없음 (픽스처는 인라인 소스):

```java
package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class MultiRootStaticIndexTest {

    private void ctrl(Path root, String pkg, String cls, String method, String path) throws Exception {
        Path dir = Files.createDirectories(root.resolve(pkg.replace('.', '/')));
        String ann = method.equals("GET") ? "GetMapping" : "PostMapping";
        Files.writeString(dir.resolve(cls + ".java"),
            "package " + pkg + ";\n" +
            "import org.springframework.web.bind.annotation.*;\n" +
            "@RestController public class " + cls + " {\n" +
            "  @" + ann + "(\"" + path + "\") public String h() { return \"x\"; }\n}");
    }

    @Test
    void selectedRootsOnly(@TempDir Path tmp) throws Exception {
        Path feature = tmp.resolve("feature");
        Path common = tmp.resolve("common");
        Path other = tmp.resolve("other");
        ctrl(feature, "f", "FeatureController", "POST", "/api/feature");
        ctrl(common, "c", "CommonController", "GET", "/api/common");
        ctrl(other, "o", "OtherController", "GET", "/api/other");

        SourceRoots roots = SourceRoots.of(List.of(feature, common), feature);
        var bundle = BuilderCli.indexStatically(roots, List.of(), null);
        Set<String> paths = bundle.index().endpoints().stream()
                .map(e -> e.path()).collect(Collectors.toSet());
        assertTrue(paths.contains("/api/feature"));
        assertTrue(paths.contains("/api/common"));
        assertFalse(paths.contains("/api/other"));   // 제외 형제 부재(REQ-001)
    }

    @Test
    void nonPrimaryMapperIncluded(@TempDir Path tmp) throws Exception {
        Path feature = tmp.resolve("feature/java");
        ctrl(feature, "f", "FeatureController", "POST", "/api/feature");
        Path commonRes = Files.createDirectories(tmp.resolve("common/resources"));
        // 실제 MapperXmlIndexer 가 인식하는 mapper XML 형식으로 작성(구현 시 기존 샘플 mapper로 형식 확인).
        Files.writeString(commonRes.resolve("CommonMapper.xml"),
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
            + "<mapper namespace=\"c.CommonMapper\"><select id=\"find\" resultType=\"int\">select 1</select></mapper>");
        SourceRoots roots = SourceRoots.of(List.of(feature), feature);
        var bundle = BuilderCli.indexStatically(roots, List.of(commonRes), null);
        assertFalse(bundle.mappers().isEmpty(), "비-primary resources mapper XML 포함(REQ-019)");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.MultiRootStaticIndexTest'`
Expected: FAIL (`indexStatically(SourceRoots,…)` 미존재).

- [ ] **Step 3: 구현** — `BuilderCli.java`:
  1. `indexStatically(Path sutSrc, Path sutResources, AuthConfig)` 본문을 `SourceRoots` 위임 형태로 재작성:

```java
    static StaticIndexBundle indexStatically(Path sutSrc, Path sutResources, AuthConfig authConfig) {
        return indexStatically(SourceRoots.single(sutSrc),
                Files.isDirectory(sutResources) ? List.of(sutResources) : List.<Path>of(), authConfig);
    }

    static StaticIndexBundle indexStatically(SourceRoots roots, List<Path> resourceDirs, AuthConfig authConfig) {
        spoon.reflect.CtModel model = SharedSpoonModel.build(roots);
        IndexResult index = new EndpointIndexer().index(model, authConfig);
        IndexResult functional = new RouterFunctionIndexer().index(model);
        if (!functional.endpoints().isEmpty()) {
            log.info("found {} functional route(s) (RouterFunction)", functional.endpoints().size());
            index = index.merge(functional);
        }
        IndexResult gateway = new GatewayRouteIndexer().index(model);
        if (!gateway.endpoints().isEmpty()) {
            log.info("found {} gateway route(s) (RouteLocator)", gateway.endpoints().size());
            index = index.merge(gateway);
        }
        WsIndexResult ws = new WsEndpointIndexer().index(model);
        KafkaIndexResult kafka = new KafkaListenerIndexer().index(model);
        ResponseDtoIndexer responseDtoIndexer = new ResponseDtoIndexer();
        List<Set<String>> dto = responseDtoIndexer.extract(model);
        List<io.graphrag.builder.index.ExternalCallSite> callSites = responseDtoIndexer.extractCallSites(model);
        Map<String, List<String>> enums = new EnumConstantExtractor().extract(model);
        List<MapperStatement> mappers = new ArrayList<>();
        for (Path resDir : resourceDirs) {
            if (Files.isDirectory(resDir)) {
                mappers.addAll(new MapperXmlIndexer().index(resDir));   // REQ-019 멀티 resources
            }
        }
        return new StaticIndexBundle(index, ws, kafka, mappers, dto, enums, callSites);
    }
```

  2. `staticIndexWithCache(BuildConfig config)`를 `sourceRoots` 사용으로 변경:

```java
    static StaticIndex staticIndexWithCache(BuildConfig config) {
        Path cacheDir = config.out().resolve("index-cache");
        SourceRoots roots = config.sourceRoots();
        // resources 결정 한 곳에 위임(REQ-011): config.sutResources()는 --sut-resources 명시 시 그 값,
        // 미지정 시 null(Task 11). null → resourceDirs 가 전 parseRoots sibling resources 순회.
        List<Path> resourceDirs = SutSrcResolver.resourceDirs(roots, config.sutResources());
        IndexManifest current = IndexCache.scan(roots, resourceDirs, config.authConfig());
        if (!config.noIncremental()) {
            Optional<StaticIndex> hit = IndexCache.load(cacheDir, current);
            if (hit.isPresent()) {
                log.info("static index: cache hit (no source change) — skipping Spoon parse");
                return hit.get();
            }
        }
        StaticIndexBundle b = indexStatically(roots, resourceDirs, config.authConfig());
        StaticIndex result = new StaticIndex(b.index(), b.ws(), b.kafka(), b.mappers(),
                b.responseDtoFieldSets(), b.enumConstants(), b.callSites());
        IndexCache.save(cacheDir, current, result);
        return result;
    }
```

  3. import 추가: `import io.graphrag.builder.index.SourceRoots;`, `import io.graphrag.builder.cli.SutSrcResolver;`(동일 패키지 — 불요), `java.util.ArrayList`(이미 있을 가능성 — 없으면 추가).

> 단일 루트(기존 e2e)에서 `config.sutResources()`는 명시되어 `resourceDirs=[그 1개]`가 되고, 결과 mapper 인덱싱은 기존과 동일(REQ-015).

- [ ] **Step 4: 통과 확인 + 기존 회귀**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.MultiRootStaticIndexTest'`
Run: `./gradlew :graph-rag-builder:test --tests '*IndexCache*' --tests '*StaticIndex*'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/MultiRootStaticIndexTest.java
git commit -m "feat(builder): 정적 인덱싱 멀티 루트(공유 모델 + 멀티 resources) (REQ-001/002/011/019)"
```

---

## Task 10: 탐색 단계 per-endpoint 추출기 `SourceRoots` 오버로드

**REQ-IDs:** REQ-014

**Files:**
- Modify: `ConstraintExtractor.java`, `LiteralCandidateExtractor.java`, `ValidationConstraintExtractor.java`, `HandlerSourceExtractor.java`, `InputOracle.java` (`SutCode`)
- Modify: `oracle/StaticLiteralOracle.java`, `oracle/LlmOracle.java` (— `SutCode.srcDir()` 소비처: 소스 파싱 호출을 `roots()`로 전환)
- Modify: `BuilderCli.java` explore() 호출부(`extractComparisons` 등 + `SutCode`/`HandlerSourceExtractor` 생성)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/MultiRootConstraintTest.java`
- 회귀 대상: `LlmOracleTest`(SutCode 보조 생성자로 컴파일 유지 확인)

**Interfaces:**
- 각 추출기: 내부 `SharedSpoonModel.build` 또는 `new Launcher().addInputResource(...)` 사용처를 `SourceRoots`를 받는 오버로드로 추가하고, 기존 `Path` 오버로드는 `SourceRoots.single`로 위임.
  - `ConstraintExtractor`: 8개 public 메서드(`extractComparisons`/`extractConjunctions`/`extractJoinGuards`/`extractEnumColumns`/`extractStateGuards`/`extract`/`reachableMethods`/`extractStringEqualities`)에 `SourceRoots` 오버로드. 내부에 private `CtModel buildModel(SourceRoots)`를 두고 모든 메서드가 이를 통해 빌드.
  - `LiteralCandidateExtractor.extract(SourceRoots, String classFqn)`.
  - `ValidationConstraintExtractor.extract(SourceRoots, String dtoQualifiedName)`.
  - `HandlerSourceExtractor(SourceRoots)` 생성자.
  - `InputOracle.SutCode`: `srcDir` 필드 타입을 `SourceRoots`로 바꾸거나, `SutCode(SourceRoots, Path bootJar)` 생성자 추가 + 소비처(`StaticLiteralOracle`,`ConcolicOracle`)가 `roots`로 모델 빌드.

- [ ] **Step 1: 실패 테스트 작성** — 비-primary 루트 핸들러의 비교 가드가 추출됨(단일 그 루트 빌드와 동등):

```java
package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MultiRootConstraintTest {
    private Path svc(Path root, String pkg, String cls, String body) throws Exception {
        Path dir = Files.createDirectories(root.resolve(pkg.replace('.', '/')));
        Files.writeString(dir.resolve(cls + ".java"),
            "package " + pkg + ";\npublic class " + cls + " {\n" + body + "\n}");
        return dir;
    }

    @Test
    void nonPrimaryHandlerConstraintsEquivalent(@TempDir Path tmp) throws Exception {
        Path primary = tmp.resolve("feature");
        Path nonPrimary = tmp.resolve("common");
        svc(primary, "f", "F", "public void a(){}");
        svc(nonPrimary, "c", "C", "public void g(int q){ if (q > 41) {} }");

        ConstraintExtractor ex = new ConstraintExtractor();
        List<ConstraintExtractor.Comparison> single =
                ex.extractComparisons(SourceRoots.single(nonPrimary));
        List<ConstraintExtractor.Comparison> multi =
                ex.extractComparisons(SourceRoots.of(List.of(primary, nonPrimary), primary));

        assertFalse(single.isEmpty(), "단일 루트에서 q>41 비교가 잡혀야");
        assertTrue(multi.size() >= single.size(), "멀티 루트가 비-primary 비교를 누락하지 않아야");
    }
}
```

> `ConstraintExtractor.Comparison` 등 실제 중첩 타입명/시그니처는 구현 시 소스로 확인해 맞춘다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.index.MultiRootConstraintTest'`
Expected: FAIL (`extractComparisons(SourceRoots)` 미존재).

- [ ] **Step 3: 구현**
  1. `ConstraintExtractor.java`: 현재 각 메서드가 `new Launcher(); addInputResource(srcDir.toString()); ...` 하는 부분을 private 헬퍼로 추출:
     ```java
     private spoon.reflect.CtModel buildModel(SourceRoots roots) {
         return io.graphrag.builder.index.SharedSpoonModel.build(roots);
     }
     ```
     각 public 메서드에 `SourceRoots` 오버로드를 추가하고 기존 `Path` 메서드는 `return xxx(SourceRoots.single(srcDir));`로 위임. (8개 메서드 모두 동일 패턴.)
     - 주의: 일부 메서드가 자체 `Launcher` 설정(noClasspath 등)을 했다면 `SharedSpoonModel.build`와 동일 설정이므로 그대로 대체 가능.
  2. `LiteralCandidateExtractor.extract(SourceRoots, String)` / `ValidationConstraintExtractor.extract(SourceRoots, String)`: 같은 방식으로 `SourceRoots` 오버로드 + `Path` 위임.
  3. `HandlerSourceExtractor`: 필드 `Path srcDir`를 `SourceRoots roots`로 바꾸고 `HandlerSourceExtractor(Path)` 생성자는 `this(SourceRoots.single(srcDir))`로 위임. 내부 모델 빌드를 `SharedSpoonModel.build(roots)`로.
  4. `InputOracle.SutCode`: `record SutCode(SourceRoots roots, Path bootJar)`로 바꾸되 **`srcDir()` 접근자를 유지**(`roots.primary()` 반환)해 기존 소비처 컴파일을 깨지 않는다. 보조 생성자 `SutCode(Path srcDir, Path bootJar)` 추가(`this(SourceRoots.single(srcDir), bootJar)`).
     - `oracle/StaticLiteralOracle.java`: `extractComparisons(sut.srcDir())`·`extractStringEqualities(sut.srcDir())` 등 **소스 파싱 호출을 `sut.roots()`로 교체**(전 루트 리터럴/비교 포함, REQ-014). primary-only로 남기면 비-primary 루트 리터럴 누락.
     - `oracle/LlmOracle.java`: 내부에서 `ValidationConstraintExtractor.extract(sut.srcDir(), …)` 또는 `HandlerSourceExtractor`를 `sut.srcDir()`로 쓰는 곳을 `sut.roots()`로 교체(grep `srcDir()` 로 소비처 확인).
     - `oracle/ConcolicOracle.java`: `sut.bootJar()`만 사용 → **변경 불요**.
     - `LlmOracleTest` 등 `new SutCode(path, jar)` 호출은 보조 생성자로 그대로 컴파일.
  5. `BuilderCli.explore()`: `config.sutSrc()` 인자를 `config.sourceRoots()`로 교체:
     - `extractComparisons(config.sourceRoots())`, `extractConjunctions(...)`, `extractJoinGuards(...)`, `extractEnumColumns(...)`, `extractStateGuards(...)`
     - `new InputOracle.SutCode(config.sourceRoots(), config.sutJar())`
     - `new HandlerSourceExtractor(config.sourceRoots())`
     - 731–750의 `literalExtractor.extract(config.sourceRoots(), ...)`, `ValidationConstraintExtractor.extract(config.sourceRoots(), ...)`, `ConstraintExtractor.reachableMethods/extract(config.sourceRoots(), ...)`

- [ ] **Step 4: 통과 확인 + 모듈 전체 회귀**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.index.MultiRootConstraintTest'`
Run: `./gradlew :graph-rag-builder:test`
Expected: PASS + 기존 추출기/오라클 테스트 전부 green(단일 루트 위임이 동작 보존).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/index/LiteralCandidateExtractor.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/index/ValidationConstraintExtractor.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/oracle/HandlerSourceExtractor.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/oracle/InputOracle.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/MultiRootConstraintTest.java
git commit -m "feat(builder): 탐색 per-endpoint 추출기 SourceRoots 전 루트 파싱 (REQ-014)"
```

---

## Task 11: `BuilderCli` main() 와이어링 (`--sut-src` 멀티 + `--endpoint` brace + 거부/로그)

**REQ-IDs:** REQ-011, REQ-012, REQ-018, REQ-004

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (96, 143–157, config 생성부)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliArgsTest.java`

**Interfaces:**
- Consumes: `SutSrcResolver.resolve`(Task 4), `GlobToken.split`(Task 1), `SutSrcResolver.resourceDirs`.
- main()이 `SourceRoots`를 만들어 `BuildConfig`에 주입(canonical 생성자 사용), `--endpoint`는 `GlobToken.split`로 토큰화, 멀티 루트 + `--incremental-base` 거부, resources 미지정 폴백 INFO 로그.

- [ ] **Step 1: 실패 테스트 작성** — args 검증 헬퍼를 통해 거부/토큰화 확인. main()은 부수효과가 크므로, 와이어링 로직을 테스트 가능한 정적 헬퍼 `buildSourceRoots(Map<String,String>)`로 추출해 테스트:

```java
package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BuilderCliArgsTest {
    @Test
    void multiRootRejectsIncremental(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        Map<String,String> opts = Map.of(
                "--sut-src", a + ", " + b,
                "--incremental-base", tmp.resolve("prev").toString());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BuilderCli.buildSourceRoots(opts));
        assertTrue(ex.getMessage().contains("incremental"));
    }

    @Test
    void singleRootAllowsIncremental(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Map<String,String> opts = Map.of(
                "--sut-src", a.toString(),
                "--incremental-base", tmp.resolve("prev").toString());
        SourceRoots roots = BuilderCli.buildSourceRoots(opts);
        assertFalse(roots.isMulti());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.BuilderCliArgsTest'`
Expected: FAIL (`buildSourceRoots` 미존재).

- [ ] **Step 3: 구현** — `BuilderCli.java`:
  1. 새 정적 헬퍼:
     ```java
     static SourceRoots buildSourceRoots(Map<String, String> options) {
         String sutSrcArg = required(options, "--sut-src");
         Path resourcesArg = options.containsKey("--sut-resources")
                 ? Path.of(options.get("--sut-resources")) : null;
         SourceRoots roots = SutSrcResolver.resolve(sutSrcArg, resourcesArg);
         if (roots.isMulti() && options.get("--incremental-base") != null) {
             throw new IllegalArgumentException(
                     "--sut-src multi-root is not supported with --incremental-base (v1)");
         }
         if (roots.isMulti() && resourcesArg == null) {
             log.info("--sut-resources not given; falling back to each source root's sibling 'resources'");
         }
         return roots;
     }
     ```
  2. main()에서 96행 `Path sutSrc = Path.of(required(options,"--sut-src"));`를 다음으로 교체:
     ```java
     SourceRoots sourceRoots = buildSourceRoots(options);
     Path sutSrc = sourceRoots.primary();
     ```
  3. 143–150행 `--endpoint` 파싱을 `GlobToken.split`로 교체:
     ```java
     List<String> endpointSelectors = List.of();
     if (options.containsKey("--endpoint")) {
         endpointSelectors = GlobToken.split(options.get("--endpoint"));
         if (endpointSelectors.isEmpty()) {
             throw new IllegalArgumentException("--endpoint given but no non-blank spec(s) provided");
         }
     }
     ```
  4. config 생성: canonical 26-arg 생성자 호출로 바꾸고 마지막 인자에 `sourceRoots` 추가(llm 뒤). `--sut-resources`는 **명시 시 그 값, 미지정 시 `null`** 로 둔다(단일/멀티 공통). null이면 `staticIndexWithCache`의 `SutSrcResolver.resourceDirs(roots, null)`가 전 루트 sibling resources를 스캔하므로, 단일 루트의 기존 동작(그 루트 sibling)도 그대로 재현된다:
     ```java
     Path sutResources = options.containsKey("--sut-resources")
             ? Path.of(options.get("--sut-resources"))
             : null;   // null → resourceDirs 가 루트별 sibling 자동 해석(단일/멀티 공통, REQ-011/015)
     ```
     주의: `config.sutResources()`가 null 가능해지므로, 이 값을 직접 `Files.isDirectory(...)`에 쓰는 다른 소비처가 있으면 null-가드를 추가한다(grep `sutResources()` 로 확인). `staticIndexWithCache`/`IndexCache.scan`/`indexStatically(SourceRoots,…)`는 이미 null-안전(`resourceDirs`가 빈 리스트/존재분만 반환).
     그리고 `new BuildConfig(...)` 마지막에 `, sourceRoots` 추가. canonical 26-arg 생성자를 직접 호출.

- [ ] **Step 4: 통과 확인 + 컴파일**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.BuilderCliArgsTest'`
Run: `./gradlew :graph-rag-builder:compileJava`
Expected: PASS + 컴파일 성공.

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliArgsTest.java
git commit -m "feat(builder): main() 멀티 루트 와이어링 + endpoint brace 토큰화 + incremental 거부 (REQ-011/012/018/004)"
```

---

## Task 12: 멀티 루트 E2E 픽스처 + 정적 E2E (형제 제외·동치·혼용·교집합)

**REQ-IDs:** REQ-001, REQ-002, REQ-003, REQ-010

**Files:**
- Create: `e2e/src/multiroot/feature/io/graphrag/sample/multiroot/feature/FeatureController.java`
- Create: `e2e/src/multiroot/common/io/graphrag/sample/multiroot/common/CommonController.java`
- Create: `e2e/src/multiroot/other/io/graphrag/sample/multiroot/other/OtherController.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/MultiRootStaticE2E.java`

> 픽스처를 별도 소스 트리(컴파일 불요 — Spoon은 소스만 파싱)로 두어 샘플 앱 빌드에 영향 없음. 각 컨트롤러는 endpoint 1개.

**Interfaces:**
- Consumes: `SutSrcResolver.resolve`, `BuilderCli.indexStatically`, `EndpointSelector.resolve`.

- [ ] **Step 1: 픽스처 3개 작성**

`FeatureController.java`:
```java
package io.graphrag.sample.multiroot.feature;
import org.springframework.web.bind.annotation.*;
@RestController public class FeatureController {
    @PostMapping("/api/feature") public String create() { return "f"; }
}
```
`CommonController.java`:
```java
package io.graphrag.sample.multiroot.common;
import org.springframework.web.bind.annotation.*;
@RestController public class CommonController {
    @GetMapping("/api/common") public String get() { return "c"; }
}
```
`OtherController.java`:
```java
package io.graphrag.sample.multiroot.other;
import org.springframework.web.bind.annotation.*;
@RestController public class OtherController {
    @GetMapping("/api/other") public String get() { return "o"; }
}
```

- [ ] **Step 2: 실패 E2E 작성** (정적 인덱싱 레벨 — SUT 부팅 불요, REQ-020 무관)

```java
package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class MultiRootStaticE2E {
    private static final Path BASE = Path.of(System.getProperty("user.dir"))
            .resolveSibling("e2e").resolve("src").resolve("multiroot");

    private Set<String> pathsOf(String sutSrcArg) {
        SourceRoots roots = SutSrcResolver.resolve(sutSrcArg, null);
        return BuilderCli.indexStatically(roots, List.of(), null).index().endpoints()
                .stream().map(e -> e.path()).collect(Collectors.toSet());
    }

    @Test
    void selectedRootsOnly() {
        Set<String> p = pathsOf(BASE + "/{feature,common}");
        assertEquals(Set.of("/api/feature", "/api/common"), p);   // REQ-001
    }

    @Test
    void braceEqualsCommaList() {
        assertEquals(pathsOf(BASE + "/{feature,common}"),
                     pathsOf(BASE + "/feature, " + BASE + "/common"));   // REQ-002
    }

    @Test
    void braceEqualsUnionOfSeparateBuilds() {
        Set<String> union = new java.util.HashSet<>(pathsOf(BASE + "/feature"));
        union.addAll(pathsOf(BASE + "/common"));
        assertEquals(union, pathsOf(BASE + "/{feature,common}"));   // REQ-002 (단독 빌드 합집합)
    }

    @Test
    void mixLiteralAndGlob() {
        Set<String> p = pathsOf(BASE + "/feature, " + BASE + "/common/**");
        assertTrue(p.contains("/api/feature"));
        assertTrue(p.contains("/api/common"));
        assertFalse(p.contains("/api/other"));   // REQ-003
    }

    @Test
    void sutSrcIntersectEndpoint() {
        SourceRoots roots = SutSrcResolver.resolve(BASE + "/{feature,common}", null);
        var bundle = BuilderCli.indexStatically(roots, List.of(), null);
        Set<String> ids = EndpointSelector.resolve(List.of("GET *"),
                bundle.index().endpoints(), bundle.ws().endpoints(), bundle.kafka().consumers());
        // feature+common 중 GET 만 → get-api-common
        assertEquals(1, ids.size());   // REQ-010
        assertTrue(ids.iterator().next().contains("common"));
    }
}
```

> 픽스처 디렉터리 구조: `e2e/src/multiroot/{feature,common,other}` 각각이 소스 루트. 패키지는 `package` 선언으로 해석되므로 디렉터리 깊이는 무관(Spoon).

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.MultiRootStaticE2E'`
Expected: FAIL (픽스처/로직 미완 시).

- [ ] **Step 4: 통과 확인** (Task 4·6·9 완료로 통과해야 함)

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.MultiRootStaticE2E'`
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
git add e2e/src/multiroot graph-rag-builder/src/test/java/io/graphrag/builder/cli/MultiRootStaticE2E.java
git commit -m "test(e2e): 멀티 루트 정적 E2E + 픽스처(feature/common/other) (REQ-001/002/003/010)"
```

---

## Task 13: `--endpoint` glob 풀 빌드 E2E (정적 풀 유지 확인)

**REQ-IDs:** REQ-005

**Files:**
- Modify: `e2e/run-e2e.sh` 기반 새 스크립트 `e2e/run-endpoint-glob-e2e.sh` (order-service 단일 루트 + `--endpoint` glob)
- Test: 스크립트가 `graph.json` 어설션(jq)

**Interfaces:** 기존 `run-e2e.sh`의 빌드 단계 패턴 재사용. SUT/컨테이너를 띄우므로 **REQ-020 teardown 적용**.

- [ ] **Step 1: 스크립트 작성** — `run-e2e.sh`를 복제·축소해 build 단계만 수행, `--endpoint 'POST /api/orders/**'` 전달, 산출 `graph.json`에서 (a) 탐색된 path가 매칭 엔드포인트로 한정, (b) 정적 endpoints 목록은 풀 유지를 jq로 확인. teardown은 `trap 'docker compose -p "$PROJ" down -v --remove-orphans' EXIT INT TERM`로 고유 project만 정리(REQ-020).

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; E2E="$ROOT/e2e"; OUT="$E2E/out-epglob"
PROJ="grb-epglob-$$"
trap 'docker compose -p "$PROJ" -f "$E2E/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true' EXIT INT TERM
"$ROOT/gradlew" -q :samples:order-service:bootJar :e2e:copyOtelAgent
rm -rf "$OUT"
COMPOSE_PROJECT_NAME="$PROJ" "$ROOT/gradlew" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --out $OUT/graph --sut-id order-service --budget-requests 30 \
  --sut-compose $E2E/docker-compose.yml \
  --endpoint 'POST /api/orders/**'"
# 정적 목록은 풀(>= 매칭 수보다 많음), 탐색 path는 매칭 엔드포인트로 한정
STATIC=$(jq '.endpoints | length' "$OUT/graph/graph.json")
EXPLORED=$(jq '[.paths[].endpointId] | unique | length' "$OUT/graph/graph.json")
echo "static endpoints=$STATIC explored endpoint groups=$EXPLORED"
[ "$STATIC" -gt "$EXPLORED" ] || { echo "FAIL: 정적 목록이 풀로 유지되지 않음"; exit 1; }
echo "PASS REQ-005"
```

- [ ] **Step 2: 실행해 실패/통과 확인**

Run: `chmod +x e2e/run-endpoint-glob-e2e.sh && ./e2e/run-endpoint-glob-e2e.sh`
Expected: 먼저 빌드 통과, 어설션 PASS. (graph.json 필드명은 실제 스키마로 확인해 jq 경로 조정.)

- [ ] **Step 3: 누수 0 확인** (REQ-020)

Run: `docker ps -a --filter "name=grb-epglob" --format '{{.Names}}'`
Expected: 빈 출력(teardown 후 자기 컨테이너 잔존 0).

- [ ] **Step 4: 커밋**

```bash
git add e2e/run-endpoint-glob-e2e.sh
git commit -m "test(e2e): --endpoint glob 풀 빌드 E2E + teardown (REQ-005/020)"
```

---

## Task 14: 하위호환 회귀 + 누수 게이트

**REQ-IDs:** REQ-015, REQ-020

**Files:**
- Modify: `e2e/run-e2e.sh` (teardown trap·고유 project 보강만, 시나리오 무변경)
- Create: `e2e/check-no-leak.sh` (스위트 후 자기 스코프 잔존 0 검사)

- [ ] **Step 1: `run-e2e.sh` teardown 보강** — 기존 docker compose 호출에 고유 `-p grb-e2e-$$` project name과 `trap ... down -v --remove-orphans` 추가(시나리오·어설션 무변경, REQ-015 보존). 무차별 정리 금지 — 그 project만.

- [ ] **Step 2: `check-no-leak.sh` 작성**

```bash
#!/usr/bin/env bash
# 스위트가 띄운 자기 스코프(label/project prefix) 잔존 0 검사. 공유 인프라 불가침.
set -euo pipefail
PREFIX="${1:-grb-}"
LEAK=$(docker ps -a --filter "name=$PREFIX" --format '{{.Names}}' || true)
if [ -n "$LEAK" ]; then echo "LEAK: $LEAK"; exit 1; fi
echo "no residual containers for prefix '$PREFIX'"
```

- [ ] **Step 3: 회귀 실행** (인프라 가용 시)

Run: `./e2e/run-e2e.sh && ./e2e/check-no-leak.sh grb-`
Expected: 기존 e2e tests>0/failures=0/errors=0 + 잔존 0. (Docker 미가용 환경이면 실행 불가를 명시하고 단위/통합으로 갈음 — `verification-before-completion`.)

- [ ] **Step 4: 커밋**

```bash
git add e2e/run-e2e.sh e2e/check-no-leak.sh
git commit -m "test(e2e): 하위호환 회귀 teardown 보강 + 누수 게이트 (REQ-015/020)"
```

---

## Task 15: 문서/CLI 사용법 동기화

**REQ-IDs:** REQ-016 (+ 설계 동기화)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (usage 문자열)
- Modify: `docs/03-graph-rag-builder.md`
- Modify: `docs/superpowers/specs/2026-06-24-sut-src-endpoint-glob-design.md` (BuildConfig 위치 편차 1줄)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliUsageTest.java`

- [ ] **Step 1: 실패 테스트 작성** — usage 문자열에 혼용 예시·glob 문법 포함:

```java
package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderCliUsageTest {
    @Test
    void documentsListGlobMix() {
        String u = BuilderCli.usage();
        assertTrue(u.contains("--sut-src"));
        assertTrue(u.contains("--endpoint"));
        assertTrue(u.contains("{"), "brace glob 예시 포함");
        assertTrue(u.contains("**"), "재귀 glob 문법 포함");
        assertTrue(u.toLowerCase().contains("glob"));
    }
}
```

> `BuilderCli.usage()`가 없으면, 기존 usage 출력(68행 주석 등)을 반환하는 정적 메서드를 신설하고 main()의 도움말 출력이 이를 쓰도록 한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.BuilderCliUsageTest'`
Expected: FAIL.

- [ ] **Step 3: 구현**
  1. `BuilderCli.usage()` 정적 메서드 신설. `--sut-src`·`--endpoint` 각각에 정확/glob/혼용 예시 + glob 문법(`*`/`**`/`{a,b}`) 한 줄씩:
     ```
     --sut-src <pattern[,pattern...]>   소스 루트(들). 리터럴/glob 혼용.
                                        예: src/main/java
                                            src/main/java/com/app/{feature,common}
                                            a/orders, a/common/**   (glob: *=세그먼트, **=재귀, {a,b}=택일)
     --endpoint <spec[,spec...]>        탐색할 단위. 정확 id / "METHOD /path" / glob 혼용.
                                        예: post-api-orders, GET /api/users/**, post-api-orders-*
     ```
  2. `docs/03-graph-rag-builder.md`의 "엔드포인트 선택(`--endpoint`)" 절(100–106행)에 glob·혼용 추가, 신규 "소스 루트 선택(`--sut-src` 멀티 루트)" 절 추가(혼용 예시·partial graph·R2/R6/N2 한계). (설계 §11 참조.)
  3. design spec §6.4 동기화(spec↔plan 편차 명시): (a) `sourceRoots`는 record **마지막** 컴포넌트 + compact 정규화(2번째 위치 대신), (b) `IndexCache.scan`/`indexStatically`는 `Path sutResources` 대신 **`List<Path> resourceDirs`**(멀티 resources)를 받음, (c) `config.sutResources()`는 **명시 시에만 non-null**(미지정 시 null → 루트별 sibling 자동 해석).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.BuilderCliUsageTest'`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliUsageTest.java \
        docs/03-graph-rag-builder.md \
        docs/superpowers/specs/2026-06-24-sut-src-endpoint-glob-design.md
git commit -m "docs(builder): CLI 사용법/문서에 정확+glob+혼용 명시 (REQ-016)"
```

---

## Task 16: 전체 회귀 + 추적 매트릭스 100% green 확정

**REQ-IDs:** 전체 (REQ-001~020 커버리지 확정)

**Files:**
- Modify: `docs/superpowers/requirements/2026-06-24-sut-src-endpoint-glob-requirements.md` (매트릭스 상태 갱신)

- [ ] **Step 1: 모듈 전체 + 의존 모듈 테스트**

Run: `./gradlew :graph-rag-builder:test`
Expected: 전부 green (신규 + 기존 회귀).

- [ ] **Step 2: E2E (인프라 가용 시)**

Run: `./e2e/run-e2e.sh && ./e2e/check-no-leak.sh grb-` 및 `./e2e/run-endpoint-glob-e2e.sh`
Expected: green + 누수 0. Docker 미가용이면 그 사실을 매트릭스/PR에 명시(`verification-before-completion`).

- [ ] **Step 3: 매트릭스 갱신** — REQ-001~020을 실제 통과 테스트명과 대조해 🟢로 갱신, Coverage 줄을 `20/20 green (100%)`로. 각 green REQ가 실제 통과 테스트에 대응하는지 테스트명 대조.

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/requirements/2026-06-24-sut-src-endpoint-glob-requirements.md
git commit -m "docs: 추적 매트릭스 100% green 확정 (REQ-001~020)"
```

---

## Self-Review

**1. Spec coverage (요구사항 ↔ task):**
- REQ-001 부분그래프 → T9/T12 · REQ-002 동치 → T12 · REQ-003 혼용 → T12 · REQ-004 fail-fast → T4 · REQ-005 endpoint glob → T6/T13 · REQ-006 endpoint 혼용 → T6 · REQ-007 정확 하위호환 → T6 · REQ-008 0매칭/형식오류 → T2/T6 · REQ-009 glob 문법 → T2(endpoint)/T4(sut-src) · REQ-010 교집합 → T12 · REQ-011 resources → T4/T9/T11 · REQ-012 incremental 거부 → T11 · REQ-013 캐시 freshness → T8 · REQ-014 비-primary 제약 → T10 · REQ-015 회귀 → T7/T14 · REQ-016 문서 → T15 · REQ-017 dedup → T4 · REQ-018 토큰화 → T1/T11 · REQ-019 mapper XML → T9 · REQ-020 teardown/누수 → T13/T14. **고아 없음.**
**2. Placeholder scan:** 모든 step에 실제 코드/명령. `Endpoint`/`ConstraintExtractor.Comparison` 등 외부 record 시그니처는 "구현 시 소스 확인" 명시(존재하는 타입, 시그니처만 대조) — 미정의 타입 참조 아님.
**3. Type consistency:** `SourceRoots.single/of/parseRoots/primary/isMulti`, `GlobToken.split`, `GlobMatcher.hasGlobMeta/matches`, `SutSrcResolver.resolve/resourceDirs`, `IndexCache.scan(SourceRoots,List<Path>,AuthConfig)`, `indexStatically(SourceRoots,List<Path>,AuthConfig)`, `BuildConfig.sourceRoots()`, `BuilderCli.buildSourceRoots/usage` — task 간 일관.
