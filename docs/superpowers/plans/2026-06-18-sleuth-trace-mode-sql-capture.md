# Sleuth(B3) trace-mode + 로그 SQL 캡처 — 구현 계획 (Spec 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레거시 Java8+Sleuth(B3)+Eventuate/Tram 비동기 MSA에서 요청별 B3 trace-id를 주입하고, 그 trace-id가 박힌 로그 라인만 상관시켜 A→B→C 전체 SQL을 캡처하는 새 `sleuth` trace-mode를 추가한다. 동시에 사용자 CLI 플래그 `--sql-capture <otel|log>` 를 `--trace-mode <otel|sleuth|none>` 로 개명한다.

**Architecture:** 기존 `SqlCaptureBackend`(begin/Scope/drain) 추상화를 그대로 따르는 신규 `SleuthLogCapture` 백엔드. `begin()`이 요청별 유니크 B3 traceId를 발급(`B3TraceId`, `TraceParent` façade)하고 `requestHeaders()`가 B3 멀티헤더를 반환, `drain()`이 수집 로그에서 traceId 일치 라인만 필터→await/quiescence→`SqlLogParser`로 파싱한다. `SqlLogParser`는 H5 `BasicBinder`(축약/풀 로거명) bind 패턴과 라인 prefix traceId 추출을 추가로 지원한다(trace-mode 독립, 로그 파싱 전 경로 공통). 멀티서비스 로그 수집을 위해 `--capture-services a,b,c` 를 추가한다.

**Tech Stack:** Java 17(빌더 자체), Gradle, JUnit 5 + AssertJ, Jackson(YAML override), docker compose(attach). 대상 SUT는 Java 8 + Hibernate 5 + Spring Cloud Sleuth(Brave/B3) + Eventuate Tram.

## Global Constraints

(스펙 `docs/superpowers/specs/2026-06-18-traceid-log-sql-capture-design.md` 에서 그대로 인용)

- **CLI 개명**: 사용자 플래그 `--sql-capture` → `--trace-mode`; 값 `otel|log` → `otel|sleuth|none`(`log`→`none`). 기본값은 `otel`. 그 외 값은 거부(throw). 내부 인터페이스 이름 `SqlCaptureBackend` 는 **유지**(실제로 SQL을 캡처).
- **상관 헤더는 활성 trace-mode가 결정·상호배타**: `otel`→`traceparent`(B3 미사용), `sleuth`→B3(`X-B3-TraceId/SpanId/Sampled` + `b3`, traceparent 미사용), `none`→없음. **B3 주입은 `sleuth` 전용**.
- **B3 주입은 OTEL agent(`otel`)에 미적용.** `sleuth` 모드는 OTEL javaagent·OTLP 리시버를 생성하지 않는다(레거시에서 `brave.Tracing` 빈 충돌 회피).
- **traceId 추출은 라인의 로그 prefix 영역(로거명 `:` 구분자 이전)만 검사** — SQL 본문/bind 값의 hex 오탐 방지. 매칭은 **대소문자 무관**, **full(32 hex) 또는 우측 64-bit(16 hex) 둘 다 허용**.
- **타임아웃 시 빈 결과 + 경고 로깅** — 조용한 성공 위장 금지. offset-window 폴백 미채택.
- **인프라 폴링 SQL 자동 배제**: 요청 traceId가 없는 백그라운드 SQL은 traceId 필터로 자동 제외. denylist 불필요.
- **결정성**: `Math.random`/`new Date` 미사용(재현성). 단 `B3TraceId` 시드에는 per-run nonce를 **외부 주입**(R5; 테스트는 고정 nonce로 결정성 유지).
- **합성 픽스처 기반 단위/통합까지가 이 계획의 DoD.** 라이브 E2E(Eventuate 샘플)는 Spec 1의 게이트로 보류.
- **과거 plan/report/old-spec 문서는 미수정**(기록): `docs/superpowers/plans/2026-06-18-otel-sql-capture.md`, `docs/superpowers/reports/...`, `docs/superpowers/specs/2026-06-18-otel-sql-capture-design.md`, `docs/archive/...`.

**빌드/테스트 명령(공통):**
- 단일 테스트: `./gradlew :graph-rag-builder:test --tests '<FQCN>' --console=plain`
- 단일 테스트 메서드: `./gradlew :graph-rag-builder:test --tests '<FQCN>.<method>' --console=plain`
- 모듈 전체: `./gradlew :graph-rag-builder:test --console=plain`
- 컴파일만: `./gradlew :graph-rag-builder:compileJava :graph-rag-builder:compileTestJava --console=plain`

---

## File Structure

**신규 생성:**
- `graph-rag-builder/src/main/java/io/graphrag/builder/capture/B3TraceId.java` — `TraceParent` 위임 façade. 요청별 `(traceId, spanId)` 발급 + B3 헤더 맵 포맷 + per-run nonce 시드.
- `graph-rag-builder/src/main/java/io/graphrag/builder/capture/SleuthLogCapture.java` — `sleuth` 모드 백엔드. B3 주입 + 수집 로그 traceId 상관 + await/quiescence + `SqlLogParser` 파싱.
- `graph-rag-builder/src/test/java/io/graphrag/builder/capture/B3TraceIdTest.java`
- `graph-rag-builder/src/test/java/io/graphrag/builder/capture/SleuthLogCaptureTest.java`
- `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliTraceModeTest.java`
- `graph-rag-builder/src/test/java/io/graphrag/builder/run/CorrelationHeaderTest.java`

**수정:**
- `capture/SqlLogParser.java` — H5 `BasicBinder` bind 패턴 추가 + `extractTraceId`/`traceIdMatches` 추가(H6/MyBatis 유지).
- `env/AttachedComposeEnvironment.java` — `Config`에 `captureServices` 추가; `logsCommand`/`upCommand` 다중 서비스 tail/up.
- `env/OverrideComposeGenerator.java` — `springApplicationJson()`에 H5 `BasicBinder=TRACE` 로그레벨 추가(스펙 §8; appService+보조 서비스 공통); `Spec`에 capture-services 보조 서비스 목록 추가; 보조 서비스에 로깅 레벨(SAJ) + 인코딩(JTO) 주입; `ENCODING_JTO`는 `public`(cli 패키지에서 참조).
- `cli/BuildConfig.java` — 필드 `sqlCapture` → `traceMode` 개명(접근자 포함).
- `cli/BuilderCli.java` — `--trace-mode` 파서, `--capture-services` 파싱, dispatch 분기(otel|sleuth|none), `runAttached`의 jto/env/리시버 모드별 분기, override에 capture-services 전달.
- `run/EndpointExplorationRunner.java` — 상관 헤더(traceparent + B3) case-insensitive 제거 후 backend 헤더 주입(테스트 가능한 순수 메서드로 추출).
- 테스트: `env/AttachedComposeEnvironmentTest.java`, `env/OverrideComposeGeneratorTest.java`, `capture/SqlLogParserTest.java`, `cli/OtelKafkaBuildAcceptanceTest.java`(주석 개명), `cli/BuilderE2eTest.java`·`cli/BuilderEndpointSelectorTest.java`(BuildConfig 마지막 인자 `"log"`→`"none"`).
- 문서: `README.md`, `docs/00-getting-started.md`, `docs/03-graph-rag-builder.md`, `docs/06-test-environment.md`, `docs/26-attach-mode.md`, `docs/27-roadmap-otel-capture-stub-seeding.md`, `e2e/run-attach-otel-e2e.sh`.

---

## Task 1: SqlLogParser — Hibernate 5 BasicBinder bind 패턴

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/SqlLogParser.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/SqlLogParserTest.java`

**Interfaces:**
- Consumes: 기존 `SqlLogParser.parse(String)` → `List<ParsedSql>`, `ParsedSql.Binding(int position, String value)`.
- Produces: `parse()`가 H5 `BasicBinder` bind 라인(축약/풀 로거명)도 인식. 시그니처 불변.

H5 로그 형식(PoC 실측): SQL은 `org.hibernate.SQL`(H6와 동일), bind는 `...BasicBinder ... binding parameter [1] as [VARCHAR] - [value]` 이며 로거명이 logback `%logger{36}`로 축약될 수 있다(`o.h.type.descriptor.sql.BasicBinder`). position은 1-based.

- [ ] **Step 1: Write the failing test**

`SqlLogParserTest.java`에 추가:

```java
    @Test
    void parsesHibernate5AbbreviatedBasicBinder() {
        String log = """
                2026-06-18T10:00:00.100+09:00 DEBUG 1 --- [tram-c-1] org.hibernate.SQL : insert into order_events (type,user_id,id) values (?,?,?)
                2026-06-18T10:00:00.101+09:00 TRACE 1 --- [tram-c-1] o.h.type.descriptor.sql.BasicBinder : binding parameter [1] as [VARCHAR] - [CREATED]
                2026-06-18T10:00:00.102+09:00 TRACE 1 --- [tram-c-1] o.h.type.descriptor.sql.BasicBinder : binding parameter [2] as [VARCHAR] - [user-1]
                2026-06-18T10:00:00.103+09:00 TRACE 1 --- [tram-c-1] o.h.type.descriptor.sql.BasicBinder : binding parameter [3] as [VARCHAR] - [evt-1]
                """;
        List<ParsedSql> parsed = SqlLogParser.parse(log);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).bindings()).containsExactly(
                new ParsedSql.Binding(1, "CREATED"),
                new ParsedSql.Binding(2, "user-1"),
                new ParsedSql.Binding(3, "evt-1"));
    }

    @Test
    void parsesHibernate5FullLoggerNameAndNull() {
        String log = """
                x DEBUG 1 --- [t] org.hibernate.SQL : update orders set status=? where id=?
                x TRACE 1 --- [t] org.hibernate.type.descriptor.sql.BasicBinder : binding parameter [1] as [VARCHAR] - [null]
                x TRACE 1 --- [t] org.hibernate.type.descriptor.sql.BasicBinder : binding parameter [2] as [BIGINT] - [42]
                """;
        List<ParsedSql> parsed = SqlLogParser.parse(log);
        assertThat(parsed.get(0).bindings()).containsExactly(
                new ParsedSql.Binding(1, "null"),
                new ParsedSql.Binding(2, "42"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.SqlLogParserTest.parsesHibernate5AbbreviatedBasicBinder' --console=plain`
Expected: FAIL — bindings 비어 있음(H5 패턴 미인식).

- [ ] **Step 3: Add the H5 bind pattern**

`SqlLogParser.java`의 기존 `HIBERNATE_BIND` 상수 바로 아래에 추가:

```java
    /** Hibernate 5 BasicBinder. 로거명은 logback %logger{36}로 축약될 수 있어 "BasicBinder"만 키로 잡는다. */
    private static final Pattern HIBERNATE_BIND_H5 = Pattern.compile(
            "BasicBinder\\b.*?binding parameter \\[(\\d+)\\] as \\[[^\\]]*\\] - \\[(.*)\\]\\s*$");
```

`parse()` 루프에서 기존 H6 bind 블록(`Matcher hibernateBind = HIBERNATE_BIND.matcher(line);` ... `continue;`) **바로 다음**에 H5 블록을 추가:

```java
            Matcher hibernateBindH5 = HIBERNATE_BIND_H5.matcher(line);
            if (hibernateBindH5.find() && currentSql != null) {
                currentBindings.add(new ParsedSql.Binding(
                        Integer.parseInt(hibernateBindH5.group(1)), hibernateBindH5.group(2)));
                continue;
            }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.SqlLogParserTest' --console=plain`
Expected: PASS (신규 2건 + 기존 H6/MyBatis 회귀 green).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/SqlLogParser.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/capture/SqlLogParserTest.java
git commit -m "feat(capture): SqlLogParser recognizes Hibernate 5 BasicBinder bind lines"
```

---

## Task 2: SqlLogParser — traceId 추출 + 매칭

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/SqlLogParser.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/SqlLogParserTest.java`

**Interfaces:**
- Produces:
  - `public static String extractTraceId(String line)` — 라인의 로그 prefix(첫 `" : "` 이전)에서 trace 토큰을 찾아 **소문자 hex** 로 반환, 없으면 `null`. MDC 덤프(`traceId=...`/`X-B3-TraceId=...`, 좌/우 경계로 오탐·절단 방지)와 Sleuth 브래킷(3-field `[svc,traceId,spanId]` + 4-field `[svc,traceId,spanId,exportable]`)을 인식.
  - `public static boolean traceIdMatches(String expected, String lineToken)` — `expected`(32 hex)와 `lineToken`이 같거나, `lineToken`이 `expected`의 우측 16 hex와 같으면 true. 대소문자 무관.

prefix만 검사하는 이유: SQL 본문·bind 값의 hex(예: `[deadbeef...]`)를 traceId로 오탐하지 않기 위함(스펙 §8, GPT I4).

- [ ] **Step 1: Write the failing test**

`SqlLogParserTest.java`에 추가:

```java
    @Test
    void extractTraceId_fromSleuthBracket() {
        String line = "x DEBUG 1 --- [order-svc,1a2b3c4d5e6f70819a2b3c4d5e6f7081,9a2b3c4d5e6f7081] "
                + "[tram-c-1] org.hibernate.SQL : select 1";
        assertThat(SqlLogParser.extractTraceId(line))
                .isEqualTo("1a2b3c4d5e6f70819a2b3c4d5e6f7081");
    }

    @Test
    void extractTraceId_fromMdcDump() {
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] traceId=1A2B3C4D5E6F70819A2B3C4D5E6F7081 c.Foo : msg"))
                .isEqualTo("1a2b3c4d5e6f70819a2b3c4d5e6f7081");
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] X-B3-TraceId=1a2b3c4d5e6f7081 c.Foo : msg"))
                .isEqualTo("1a2b3c4d5e6f7081");
    }

    @Test
    void extractTraceId_ignoresHexInSqlBodyAndBindValue() {
        // " : " 이후(SQL 본문/bind 값)의 hex는 trace로 잡히면 안 된다
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] org.hibernate.SQL : select * from t where id='deadbeefdeadbeef'"))
                .isNull();
        assertThat(SqlLogParser.extractTraceId(
                "x TRACE 1 --- [t] o.h.type.descriptor.sql.BasicBinder : binding parameter [1] as [VARCHAR] - [cafebabecafebabe]"))
                .isNull();
    }

    @Test
    void traceIdMatches_fullAndRight64BitAndCaseInsensitive() {
        String full = "1a2b3c4d5e6f70819a2b3c4d5e6f7081";
        assertThat(SqlLogParser.traceIdMatches(full, full)).isTrue();
        assertThat(SqlLogParser.traceIdMatches(full, "1A2B3C4D5E6F70819A2B3C4D5E6F7081")).isTrue();
        assertThat(SqlLogParser.traceIdMatches(full, "9a2b3c4d5e6f7081")).isTrue();   // 우측 16 hex
        assertThat(SqlLogParser.traceIdMatches(full, "0000000000000000")).isFalse();
    }

    // ↓ 리뷰 반영: 4-field Sleuth 브래킷 + MDC 키 좌/우 경계
    @Test
    void extractTraceId_fromFourFieldSleuthBracket() {
        // Sleuth 1.x/2.x (Java8 레거시 기본): [app,traceId,spanId,exportable]
        String line = "x DEBUG 1 --- [order-svc,1a2b3c4d5e6f70819a2b3c4d5e6f7081,9a2b3c4d5e6f7081,true] "
                + "[tram-c-1] org.hibernate.SQL : select 1";
        assertThat(SqlLogParser.extractTraceId(line))
                .isEqualTo("1a2b3c4d5e6f70819a2b3c4d5e6f7081");
    }

    @Test
    void extractTraceId_doesNotFalseMatchLongerMdcKey() {
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] myTraceId=1a2b3c4d5e6f70819a2b3c4d5e6f7081 c.Foo : msg"))
                .isNull();
    }

    @Test
    void extractTraceId_rejectsOverLengthToken() {
        assertThat(SqlLogParser.extractTraceId(
                "x DEBUG 1 --- [t] traceId=1a2b3c4d5e6f70819a2b3c4d5e6f7081abcdef01 c.Foo : msg"))
                .isNull();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.SqlLogParserTest.extractTraceId_fromSleuthBracket' --console=plain`
Expected: FAIL — `extractTraceId` 메서드 없음(컴파일 에러).

- [ ] **Step 3: Implement extraction + matching**

`SqlLogParser.java`에 패턴 상수 추가(기존 패턴 상수들 아래):

```java
    /**
     * 로그 prefix(첫 " : " 이전)에서 trace 토큰 후보를 찾는다.
     * 좌측 경계(?<![A-Za-z0-9-])로 myTraceId= 같은 더 긴 키의 접미 오탐을 막고,
     * 우측 경계(?![0-9a-fA-F])로 32 초과 토큰의 묵음 절단을 막는다(둘 다 거부).  // 리뷰 반영
     */
    private static final Pattern MDC_TRACE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9-])(?:traceId|X-B3-TraceId)=([0-9a-fA-F]{16,32})(?![0-9a-fA-F])");
    /**
     * Sleuth MDC 브래킷. 3-field [app,traceId,spanId] (Sleuth 3.x) 와
     * 4-field [app,traceId,spanId,exportable] (Sleuth 1.x/2.x, Java8 레거시 기본) 둘 다 매칭.  // 리뷰 반영
     */
    private static final Pattern SLEUTH_BRACKET = Pattern.compile(
            "\\[[^,\\]]*,([0-9a-fA-F]{16,32}),[0-9a-fA-F]{1,32}(?:,[^\\]]*)?\\]");
```

그리고 `private SqlLogParser() {}` 아래(또는 클래스 끝)에 public static 메서드 추가:

```java
    /**
     * 라인의 로그 prefix(로거명 ":" 구분자 이전)에서만 trace 토큰을 추출한다(소문자 hex).
     * SQL 본문/bind 값의 hex를 trace로 오탐하지 않도록 " : " 이후는 보지 않는다.
     */
    public static String extractTraceId(String line) {
        int sep = line.indexOf(" : ");
        String prefix = sep < 0 ? line : line.substring(0, sep);
        Matcher mdc = MDC_TRACE.matcher(prefix);
        if (mdc.find()) {
            return mdc.group(1).toLowerCase(java.util.Locale.ROOT);
        }
        Matcher bracket = SLEUTH_BRACKET.matcher(prefix);
        if (bracket.find()) {
            return bracket.group(1).toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }

    /** expected(32 hex)와 lineToken이 같거나, lineToken이 expected의 우측 16 hex와 같으면 true(대소문자 무관). */
    public static boolean traceIdMatches(String expected, String lineToken) {
        if (expected == null || lineToken == null) {
            return false;
        }
        String e = expected.toLowerCase(java.util.Locale.ROOT);
        String t = lineToken.toLowerCase(java.util.Locale.ROOT);
        if (e.equals(t)) {
            return true;
        }
        return e.length() == 32 && t.equals(e.substring(16));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.SqlLogParserTest' --console=plain`
Expected: PASS (신규 7건[기본 4 + 리뷰 반영 3] + 기존 전부 green = 15).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/SqlLogParser.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/capture/SqlLogParserTest.java
git commit -m "feat(capture): SqlLogParser extracts/matches trace-id from log line prefix"
```

---

## Task 3: B3TraceId — TraceParent façade + B3 헤더

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/B3TraceId.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/B3TraceIdTest.java`

**Interfaces:**
- Consumes: `TraceParent(String runId)`, `TraceParent.next()` → `TraceParent.Ids(traceId, spanId)`.
- Produces:
  - `B3TraceId(String runId, String nonce)` — 시드 = `runId + ":" + nonce`(R5: per-run nonce로 동일 커밋 동시 실행 충돌 방지; nonce 외부 주입으로 테스트 결정성 유지).
  - `B3TraceId.Ids next()` — 요청별 단조 발급.
  - `B3TraceId.Ids` (record): `traceId()`(32 hex), `spanId()`(16 hex), `Map<String,String> headers()`.
  - `headers()` 키: `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-Sampled`(="1"), `b3`(="`<traceId>-<spanId>-1`").

- [ ] **Step 1: Write the failing test**

`B3TraceIdTest.java`:

```java
package io.graphrag.builder.capture;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class B3TraceIdTest {

    @Test
    void headers_areB3MultiHeaderFormat() {
        B3TraceId.Ids ids = new B3TraceId("run-1", "nonce-1").next();
        assertThat(ids.traceId()).matches("[0-9a-f]{32}");
        assertThat(ids.spanId()).matches("[0-9a-f]{16}");
        Map<String, String> h = ids.headers();
        assertThat(h).containsEntry("X-B3-TraceId", ids.traceId());
        assertThat(h).containsEntry("X-B3-SpanId", ids.spanId());
        assertThat(h).containsEntry("X-B3-Sampled", "1");
        assertThat(h).containsEntry("b3", ids.traceId() + "-" + ids.spanId() + "-1");
    }

    @Test
    void deterministic_sameRunAndNonceSameSequence() {
        assertThat(new B3TraceId("run-1", "n").next().traceId())
                .isEqualTo(new B3TraceId("run-1", "n").next().traceId());
    }

    @Test
    void nonce_disambiguatesSameRunId() {
        // 동일 commit(runId) 동시 실행이라도 nonce가 다르면 trace 시퀀스가 충돌하지 않는다(R5)
        assertThat(new B3TraceId("run-1", "nonceA").next().traceId())
                .isNotEqualTo(new B3TraceId("run-1", "nonceB").next().traceId());
    }

    @Test
    void unique_acrossRequests() {
        B3TraceId b3 = new B3TraceId("run-1", "n");
        assertThat(b3.next().traceId()).isNotEqualTo(b3.next().traceId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.B3TraceIdTest' --console=plain`
Expected: FAIL — `B3TraceId` 클래스 없음(컴파일 에러).

- [ ] **Step 3: Implement B3TraceId**

`B3TraceId.java`:

```java
package io.graphrag.builder.capture;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * sleuth 모드용 B3 trace-id 발급기. TraceParent(결정적 32-hex traceId / 16-hex spanId 생성)에 위임하고
 * B3 멀티헤더 포맷만 책임진다. 시드는 runId + per-run nonce(R5): 동일 commit 동시 실행이 trace 시퀀스를
 * 재생→충돌→캡처 교차오염하지 않도록 nonce로 분리하되, nonce를 외부 주입해 테스트 결정성을 유지한다.
 */
public final class B3TraceId {

    private final TraceParent delegate;

    public B3TraceId(String runId, String nonce) {
        // null이 "null" 문자열로 묵음 처리되면 nonce 격리(R5)가 깨지므로 fail-fast. (리뷰 반영)
        this.delegate = new TraceParent(
                Objects.requireNonNull(runId, "runId") + ":" + Objects.requireNonNull(nonce, "nonce"));
    }

    public Ids next() {
        TraceParent.Ids d = delegate.next();
        return new Ids(d.traceId(), d.spanId());
    }

    public record Ids(String traceId, String spanId) {
        public Map<String, String> headers() {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("X-B3-TraceId", traceId);
            h.put("X-B3-SpanId", spanId);
            h.put("X-B3-Sampled", "1");
            h.put("b3", traceId + "-" + spanId + "-1");
            return h;
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.B3TraceIdTest' --console=plain`
Expected: PASS (4건).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/B3TraceId.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/capture/B3TraceIdTest.java
git commit -m "feat(capture): add B3TraceId facade over TraceParent for sleuth mode"
```

---

## Task 4: SleuthLogCapture — sleuth 백엔드 (await/quiescence + traceId 상관)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/SleuthLogCapture.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/SleuthLogCaptureTest.java`

**Interfaces:**
- Consumes: `SqlCaptureBackend`/`Scope`, `SutHandle`(`logOffset()`, `readLogRange(start,end)`), `B3TraceId`(`next()`, `Ids.headers()`), `SqlLogParser.extractTraceId/traceIdMatches/parse`.
- Produces:
  - `SleuthLogCapture(SutHandle sut, B3TraceId b3)`.
  - `begin()` → `Scope`. `requestHeaders()` → B3 멀티헤더. `drain()` → traceId 일치 라인만 파싱한 `List<ParsedSql>`(순서 보존).
  - 타이밍 상수(테스트 접근용 package-private): `FIRST_MATCH_TIMEOUT_MILLIS`(≈3000), `OVERALL_TIMEOUT_MILLIS`(≈15000), `QUIESCENCE_MILLIS`(≈300), `POLL_MILLIS`(≈50).

**동작:** `drain()`은 수집 로그 `[logStart, 현재offset)` 를 폴링하며 `extractTraceId`+`traceIdMatches`로 내 traceId 라인만 누적한다. (1) `FIRST_MATCH_TIMEOUT` 내 첫 일치가 없으면 **즉시 빈 결과**(SQL 없는 요청 — 400/검증실패/캐시 등; 정상, debug 로그). (2) 첫 일치 후에는 일치 라인 수가 늘면 quiescence 창을 리셋하고, `QUIESCENCE_MILLIS` 동안 추가 일치가 없으면 종료한다. (3) `OVERALL_TIMEOUT`까지 quiescence에 도달 못 하면 **경고 + 빈 결과**(완전성/순서 신뢰 불가, 조용한 성공 위장 금지). 종료 시 일치 라인들을 `\n`으로 join → `SqlLogParser.parse(...)`.

- [ ] **Step 1: Write the failing test**

`SleuthLogCaptureTest.java`:

```java
package io.graphrag.builder.capture;

import io.graphrag.builder.env.SutHandle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SleuthLogCaptureTest {

    /** 비동기로 자라는 수집 로그를 흉내내는 SutHandle. setLog로 내용 교체. */
    private static final class GrowingSut implements SutHandle {
        final AtomicReference<String> log = new AtomicReference<>("");
        void setLog(String s) { log.set(s); }
        public String baseUri() { return ""; }
        public long logOffset() { return log.get().getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
        public String readLog() { return log.get(); }
        public String readLogFrom(long o) { return readLogRange(o, logOffset()); }
        public String readLogRange(long start, long end) {
            byte[] b = log.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int s = (int) Math.min(start, b.length), e = (int) Math.min(end, b.length);
            return new String(b, s, Math.max(0, e - s), java.nio.charset.StandardCharsets.UTF_8);
        }
        public void stop() { }
    }

    // spanId 자리는 hex여야 SLEUTH_BRACKET이 매칭한다(임의 16-hex 사용).
    private static final String SPAN = "9a2b3c4d5e6f7081";
    private static String h5Sql(String trace, String sql) {
        return "x DEBUG 1 --- [order-svc," + trace + "," + SPAN + "] [c-1] org.hibernate.SQL : " + sql;
    }
    private static String h5Bind(String trace, int pos, String val) {
        return "x TRACE 1 --- [order-svc," + trace + "," + SPAN + "] [c-1] "
                + "o.h.type.descriptor.sql.BasicBinder : binding parameter [" + pos + "] as [VARCHAR] - [" + val + "]";
    }

    @Test
    void drain_returnsOnlyMatchingTraceLinesInOrder_excludingInfraAndOtherRequests() throws Exception {
        GrowingSut sut = new GrowingSut();
        // 첫 요청 trace를 결정적으로 얻기
        B3TraceId.Ids ids = new B3TraceId("run-1", "n").next();
        String mine = ids.traceId();
        String other = "ffffffffffffffffffffffffffffffff";

        SleuthLogCapture capture = new SleuthLogCapture(sut, new B3TraceId("run-1", "n"));
        SqlCaptureBackend.Scope scope = capture.begin();
        assertThat(scope.requestHeaders()).containsEntry("X-B3-TraceId", mine);

        // 비동기로 로그가 채워진다: 인프라(trace 없음) + 타 요청(other) + 내 요청(mine) 인터리브
        Thread writer = new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("x DEBUG 1 --- [relay] org.hibernate.SQL : select id from message where state='PUBLISHED'\n");
            sut.setLog(sb.toString());
            sleep(60);
            sb.append(h5Sql(other, "select 1 from dual")).append("\n");
            sut.setLog(sb.toString());
            sleep(60);
            sb.append(h5Sql(mine, "insert into order_events (type) values (?)")).append("\n");
            sb.append(h5Bind(mine, 1, "CREATED")).append("\n");
            sut.setLog(sb.toString());
        });
        writer.start();

        List<ParsedSql> drained = scope.drain();
        writer.join();

        assertThat(drained).extracting(ParsedSql::sql)
                .containsExactly("insert into order_events (type) values (?)");
        assertThat(drained.get(0).bindings())
                .containsExactly(new ParsedSql.Binding(1, "CREATED"));
    }

    @Test
    void drain_waitsForLateArrivingBindLineWithinQuiescenceWindow() {
        // 스펙 §10(Sonnet I8): 첫 일치가 일찍 와도 quiescence 창 내 추가 라인까지 기다려 둘 다 반환.
        // wall-clock 경쟁(flaky) 대신 read-count 기반 결정적 fake: 첫 readLogRange는 SQL-only,
        // 이후엔 SQL+bind → drain이 1→2 증가를 관측해 quietUntil 리셋 경로를 반드시 탄다. (리뷰 반영)
        String mine = new B3TraceId("run-2", "n").next().traceId();
        String sqlOnly = h5Sql(mine, "insert into order_events (type) values (?)") + "\n";
        String sqlAndBind = sqlOnly + h5Bind(mine, 1, "CREATED") + "\n";
        SutHandle sut = new SutHandle() {
            final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            private String current() { return reads.getAndIncrement() == 0 ? sqlOnly : sqlAndBind; }
            public String baseUri() { return ""; }
            public long logOffset() { return 0; }
            public String readLog() { return current(); }
            public String readLogFrom(long o) { return current(); }
            public String readLogRange(long s, long e) { return current(); }
            public void stop() { }
        };
        SleuthLogCapture capture = new SleuthLogCapture(sut, new B3TraceId("run-2", "n"));
        SqlCaptureBackend.Scope scope = capture.begin();

        List<ParsedSql> drained = scope.drain();

        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).bindings())
                .containsExactly(new ParsedSql.Binding(1, "CREATED"));   // 늦게 온 bind까지 포함
    }

    @Test
    void drain_returnsEmptyQuickly_whenNoMatchingLines() {
        GrowingSut sut = new GrowingSut();
        sut.setLog("x DEBUG 1 --- [relay] org.hibernate.SQL : select id from message\n");
        SleuthLogCapture capture = new SleuthLogCapture(sut, new B3TraceId("run-1", "n"));
        SqlCaptureBackend.Scope scope = capture.begin();

        long t0 = System.nanoTime();
        List<ParsedSql> drained = scope.drain();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(drained).isEmpty();
        // 첫 일치가 없으면 FIRST_MATCH_TIMEOUT 부근에서 조기 반환(OVERALL_TIMEOUT까지 안 감)
        assertThat(elapsedMs).isLessThan(SleuthLogCapture.OVERALL_TIMEOUT_MILLIS);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

> 참고: `drain_returnsEmptyQuickly`를 빠르게 하려면 `FIRST_MATCH_TIMEOUT_MILLIS`가 작아야 한다. 기본 3000ms면 이 테스트는 ~3s 소요(허용). 더 빠르게 하려면 Step 3에서 상수를 package-private non-final로 두고 테스트에서 낮추는 변형을 써도 되지만, 기본값 유지로 단순화한다.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.SleuthLogCaptureTest' --console=plain`
Expected: FAIL — `SleuthLogCapture` 클래스 없음(컴파일 에러).

- [ ] **Step 3: Implement SleuthLogCapture**

`SleuthLogCapture.java`:

```java
package io.graphrag.builder.capture;

import io.graphrag.builder.env.SutHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * sleuth 모드 backend: 요청별 유니크 B3 traceId를 주입하고, 수집 로그에서 그 traceId가 박힌 라인만
 * 상관(await→quiescence)해 SqlLogParser로 환원한다. traceId 불일치 = 다른 요청·인프라 폴링 SQL →
 * 자동 배제(denylist 불필요). 비동기(A→B→C, Tram) SQL을 요청 단위로 회수한다.
 */
public final class SleuthLogCapture implements SqlCaptureBackend {

    private static final Logger log = LoggerFactory.getLogger(SleuthLogCapture.class);

    /** 첫 일치 대기(없으면 SQL 없는 요청으로 보고 즉시 빈 결과). */
    static final long FIRST_MATCH_TIMEOUT_MILLIS = 3_000;
    /** drain 전체 상한(Tram 비동기 지연 고려). 도달 시 경고 + 빈 결과. */
    static final long OVERALL_TIMEOUT_MILLIS = 15_000;
    /** 마지막 일치 이후 이 시간 동안 추가 일치가 없으면 완료로 간주. */
    static final long QUIESCENCE_MILLIS = 300;
    static final long POLL_MILLIS = 50;

    private final SutHandle sut;
    private final B3TraceId b3;

    public SleuthLogCapture(SutHandle sut, B3TraceId b3) {
        this.sut = sut;
        this.b3 = b3;
    }

    @Override
    public Scope begin() {
        B3TraceId.Ids ids = b3.next();
        long logStart = sut.logOffset();
        return new SleuthScope(ids, logStart);
    }

    private final class SleuthScope implements Scope {
        private final B3TraceId.Ids ids;
        private final long logStart;

        SleuthScope(B3TraceId.Ids ids, long logStart) {
            this.ids = ids;
            this.logStart = logStart;
        }

        @Override public Map<String, String> requestHeaders() {
            return ids.headers();
        }

        @Override public List<ParsedSql> drain() {
            return drain(OVERALL_TIMEOUT_MILLIS);
        }

        @Override public List<ParsedSql> drain(long timeoutMillis) {
            long startNanos = System.nanoTime();
            long overallDeadline = startNanos + timeoutMillis * 1_000_000L;
            // caller가 더 짧은 timeout을 주면(예: KafkaCaptureRunner의 VARIANT_SETTLE_MILLIS) 그것을 존중.
            long firstMatchDeadline = Math.min(overallDeadline,
                    startNanos + FIRST_MATCH_TIMEOUT_MILLIS * 1_000_000L);

            int matchCount = matchingLines().size();
            // (1) 첫 일치 대기
            while (matchCount == 0 && System.nanoTime() < firstMatchDeadline) {
                sleep(POLL_MILLIS);
                matchCount = matchingLines().size();
            }
            if (matchCount == 0) {
                log.debug("sleuth: no SQL lines for trace {} within first-match window (empty result)",
                        ids.traceId());
                return List.of();
            }
            // (2) quiescence: 추가 일치가 멈출 때까지
            long quietUntil = System.nanoTime() + QUIESCENCE_MILLIS * 1_000_000L;
            while (System.nanoTime() < overallDeadline) {
                sleep(POLL_MILLIS);
                int now = matchingLines().size();
                if (now > matchCount) {
                    matchCount = now;
                    quietUntil = System.nanoTime() + QUIESCENCE_MILLIS * 1_000_000L;
                } else if (System.nanoTime() >= quietUntil) {
                    return SqlLogParser.parse(String.join("\n", matchingLines()));
                }
            }
            // (3) overall timeout — 완전성/순서 신뢰 불가 → 경고 + 빈 결과
            log.warn("sleuth: drain timed out before quiescence for trace {} ({} matching line(s) seen); "
                    + "returning empty to avoid partial/misordered capture", ids.traceId(), matchCount);
            return List.of();
        }

        private List<String> matchingLines() {
            // 매 폴링마다 전체 슬라이스를 재스캔(O(n) per poll). 합성 픽스처 DoD에는 충분.
            // 라이브 attach(Spec 1)에서 긴 캡처 시 O(n^2)가 될 수 있어 델타 스캔 최적화는 그때 검토.
            String slice = sut.readLogRange(logStart, sut.logOffset());
            List<String> out = new ArrayList<>();
            for (String line : slice.split("\\R")) {
                String token = SqlLogParser.extractTraceId(line);
                if (token != null && SqlLogParser.traceIdMatches(ids.traceId(), token)) {
                    out.add(line);
                }
            }
            return out;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.SleuthLogCaptureTest' --console=plain`
Expected: PASS (3건; `drain_returnsEmptyQuickly`는 ~3s 소요 정상, late-arrival는 결정적 ~quiescence).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/SleuthLogCapture.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/capture/SleuthLogCaptureTest.java
git commit -m "feat(capture): add SleuthLogCapture (B3 trace-id log correlation backend)"
```

---

## Task 5: AttachedComposeEnvironment — 멀티서비스 로그 수집(`--capture-services`)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/env/AttachedComposeEnvironment.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/AttachedComposeEnvironmentTest.java`

**Interfaces:**
- Produces:
  - `Config`에 `List<String> captureServices` 필드 추가(record 마지막 인자). null/빈 리스트면 `[appService]` 로 정규화(compact constructor).
  - `logsCommand(Config)` → `docker compose ... logs --no-log-prefix -f <s1> <s2> ...`(captureServices 전체).
  - `upCommand(Config)` → `... up -d --wait <s1> <s2> ...`(captureServices 전체; C가 떠야 로그 발생).

기존 단일 appService 호출부 호환: captureServices 미지정 시 `[appService]` 로 정규화돼 기존 커맨드와 동일.

- [ ] **Step 1: Update the failing test**

`AttachedComposeEnvironmentTest.java` 의 `cfg()`를 captureServices 인자 포함으로 바꾸고, 기존 단일 케이스는 그대로 통과하도록 + 다중 케이스 추가:

```java
    private AttachedComposeEnvironment.Config cfg() {
        return new AttachedComposeEnvironment.Config(
                Path.of("/p/docker-compose.yml"), Path.of("/p/.grb/override.yml"),
                "app", "grb-attach",
                "http://localhost:58080",
                "jdbc:postgresql://localhost:55432/app", "app", "app",
                "localhost", 16300, null, "/actuator/health", 120,
                List.of());   // captureServices 미지정 → [appService]로 정규화
    }
    private AttachedComposeEnvironment.Config multiCfg() {
        return new AttachedComposeEnvironment.Config(
                Path.of("/p/docker-compose.yml"), Path.of("/p/.grb/override.yml"),
                "a", "grb-attach",
                "http://localhost:58080",
                "jdbc:postgresql://localhost:55432/app", "app", "app",
                "localhost", 16300, null, "/actuator/health", 120,
                List.of("a", "b", "c"));
    }
```

기존 `logsCommandFollowsAppServiceNoPrefix`/`upCommand...`/`downCommand...` 는 그대로 두고(default = [app] 동등), 다중 케이스 2건 추가:

```java
    @Test void logsCommandFollowsAllCaptureServicesNoPrefix() {
        List<String> cmd = AttachedComposeEnvironment.logsCommand(multiCfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "logs","--no-log-prefix","-f","a","b","c"), cmd);
    }
    @Test void upCommandWaitsForAllCaptureServices() {
        List<String> cmd = AttachedComposeEnvironment.upCommand(multiCfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "up","-d","--wait","a","b","c"), cmd);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.env.AttachedComposeEnvironmentTest' --console=plain`
Expected: FAIL — `Config` 생성자 인자 수 불일치(컴파일 에러).

- [ ] **Step 3: Add captureServices to Config + commands**

`Config` record에 `List<String> captureServices` 추가 + compact constructor 정규화 + **기존 13-arg 호출부 호환 생성자**(BuilderCli가 아직 13-arg로 호출 → Task 5에서 컴파일을 깨지 않기 위함):

```java
    public record Config(Path userCompose, Path overrideCompose, String appService, String projectName,
                         String appBaseUri, String jdbcUrl, String dbUser, String dbPass,
                         String coverageHost, int coveragePort, String kafkaBootstrap,
                         String healthPath, int readyTimeoutSeconds, List<String> captureServices) {
        public Config {
            captureServices = (captureServices == null || captureServices.isEmpty())
                    ? List.of(appService) : List.copyOf(captureServices);
        }

        /** 기존 13-arg 호출부 호환 (captureServices 미지정 → [appService]). */
        public Config(Path userCompose, Path overrideCompose, String appService, String projectName,
                      String appBaseUri, String jdbcUrl, String dbUser, String dbPass,
                      String coverageHost, int coveragePort, String kafkaBootstrap,
                      String healthPath, int readyTimeoutSeconds) {
            this(userCompose, overrideCompose, appService, projectName, appBaseUri, jdbcUrl, dbUser, dbPass,
                    coverageHost, coveragePort, kafkaBootstrap, healthPath, readyTimeoutSeconds, List.of());
        }
    }
```

`upCommand`/`logsCommand`를 captureServices 전체로:

```java
    static List<String> upCommand(Config c) {
        // capture-services 전체 기동(+depends_on): A→B→C가 떠야 C 로그가 발생한다.
        List<String> cmd = baseCompose(c); cmd.addAll(List.of("up", "-d", "--wait"));
        cmd.addAll(c.captureServices()); return cmd;
    }
    static List<String> logsCommand(Config c) {
        // 한 파일에 인터리브 tail. traceId가 상관 키이므로 --no-log-prefix 유지.
        List<String> cmd = baseCompose(c); cmd.addAll(List.of("logs", "--no-log-prefix", "-f"));
        cmd.addAll(c.captureServices()); return cmd;
    }
```

(필요 시 파일 상단에 `import java.util.List;` 가 이미 있는지 확인 — 있음.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.env.AttachedComposeEnvironmentTest' --console=plain`
Expected: PASS (기존 3건 + 신규 2건).

> 컴파일 green 유지: 위 13-arg 호환 생성자 덕분에 `BuilderCli.runAttached`의 기존 13-arg `Config` 생성은 그대로 컴파일된다(captureServices=[appService] 정규화). 따라서 이 Task만으로 모듈 전체 컴파일·테스트가 깨지지 않는다. Task 8에서 13-arg→14-arg(`at.captureServices()` 전달)로 갱신한다.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/env/AttachedComposeEnvironment.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/env/AttachedComposeEnvironmentTest.java
git commit -m "feat(env): multi-service log capture via Config.captureServices"
```

---

## Task 6: OverrideComposeGenerator — 보조 capture-service에 로깅/인코딩 주입

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/env/OverrideComposeGenerator.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/OverrideComposeGeneratorTest.java`

**Interfaces:**
- Produces:
  - `Spec`에 `List<String> extraLogServices` 추가(appService 외 capture-service들). 각 보조 서비스에는 **SPRING_APPLICATION_JSON(로깅 레벨, batch 미적용) + JAVA_TOOL_OPTIONS(인코딩만)** 을 주입(에이전트/포트/볼륨 없음 — 그건 appService 전용).
  - 보조 서비스 인코딩 JTO = `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8`.
  - 기존 11-arg/13-arg 생성자는 `extraLogServices = List.of()` 로 위임(하위호환).

appService는 H5 SQL을 직접 실행하지 않더라도 C(보조 서비스)가 실행 → C의 로그에 H5 SQL이 나오려면 C에 로깅 레벨이 주입돼야 한다.

- [ ] **Step 1: Write the failing test**

`OverrideComposeGeneratorTest.java`에 추가:

```java
    @Test
    void injectsLoggingAndEncodingOntoExtraCaptureServices() throws Exception {
        var spec = new OverrideComposeGenerator.Spec(
                "a", "/host/agents", 8080, 58080, 6300, 16300,
                "-javaagent:/grb-agents/jacocoagent.jar=output=tcpserver,address=*,port=6300",
                Map.of(), Map.of(), false, false,
                java.util.List.of("b", "c"));   // extraLogServices
        String yaml = new OverrideComposeGenerator().generate(spec);
        JsonNode services = new YAMLMapper().readTree(yaml).path("services");

        for (String svc : java.util.List.of("b", "c")) {
            JsonNode env = services.path(svc).path("environment");
            String saj = env.path("SPRING_APPLICATION_JSON").asText();
            assertTrue(saj.contains("logging.level.org.hibernate.SQL"), svc + " H6 SQL 로깅레벨");
            // H5 BasicBinder 로그레벨이 주입돼야 H5 SUT가 bind 라인을 출력한다(스펙 §8)
            assertTrue(saj.contains("logging.level.org.hibernate.type.descriptor.sql.BasicBinder"),
                    svc + " H5 BasicBinder 로깅레벨");
            assertTrue(env.path("JAVA_TOOL_OPTIONS").asText().contains("-Dfile.encoding=UTF-8"),
                    svc + " 인코딩 주입");
            // 보조 서비스엔 에이전트/포트/볼륨 미주입
            assertFalse(env.path("JAVA_TOOL_OPTIONS").asText().contains("jacocoagent.jar"));
            assertTrue(services.path(svc).path("ports").isMissingNode());
            assertTrue(services.path(svc).path("volumes").isMissingNode());
        }
        // appService(a)는 기존대로 에이전트/포트 유지 + H5 로깅레벨도 포함
        assertTrue(services.path("a").path("environment").path("JAVA_TOOL_OPTIONS").asText()
                .contains("jacocoagent.jar"));
        assertTrue(services.path("a").path("environment").path("SPRING_APPLICATION_JSON").asText()
                .contains("logging.level.org.hibernate.type.descriptor.sql.BasicBinder"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.env.OverrideComposeGeneratorTest.injectsLoggingAndEncodingOntoExtraCaptureServices' --console=plain`
Expected: FAIL — `Spec` 생성자 인자 불일치(컴파일 에러).

- [ ] **Step 3: Add extraLogServices to Spec + generate()**

`Spec` record에 `List<String> extraLogServices` 추가(맨 끝)하고, 기존 두 편의 생성자가 `List.of()` 로 위임하도록 수정. 먼저 import 추가(파일 상단): `import java.util.List;`

`Spec` 정의 변경:

```java
    public record Spec(String appService, String hostAgentsDir,
                       int appContainerPort, int appHostPort,
                       int jacocoContainerPort, int jacocoHostPort,
                       String javaToolOptions, Map<String, String> mybatisNamespaces,
                       Map<String, String> extraEnv,
                       boolean addHostGateway, boolean disableBatch,
                       List<String> extraLogServices) {

        /** 9-arg 편의 생성자 (기존 호출부 호환; host-gateway/batch/extraLogServices 비활성). */
        public Spec(String appService, String hostAgentsDir,
                    int appContainerPort, int appHostPort,
                    int jacocoContainerPort, int jacocoHostPort,
                    String javaToolOptions, Map<String, String> mybatisNamespaces,
                    Map<String, String> extraEnv) {
            this(appService, hostAgentsDir, appContainerPort, appHostPort,
                    jacocoContainerPort, jacocoHostPort, javaToolOptions, mybatisNamespaces,
                    extraEnv, false, false, List.of());
        }

        /** 11-arg 생성자 (addHostGateway/disableBatch 사용 호출부 호환; 보조 서비스 없음). */
        public Spec(String appService, String hostAgentsDir,
                    int appContainerPort, int appHostPort,
                    int jacocoContainerPort, int jacocoHostPort,
                    String javaToolOptions, Map<String, String> mybatisNamespaces,
                    Map<String, String> extraEnv,
                    boolean addHostGateway, boolean disableBatch) {
            this(appService, hostAgentsDir, appContainerPort, appHostPort,
                    jacocoContainerPort, jacocoHostPort, javaToolOptions, mybatisNamespaces,
                    extraEnv, addHostGateway, disableBatch, List.of());
        }
    }
```

상수 추가(클래스 본문, `YAML` 옆). **`public`** 이어야 cli 패키지의 `BuilderCli`(Task 8)가 참조 가능:

```java
    public static final String ENCODING_JTO = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8";
```

**H5 로그레벨 주입 추가(스펙 §8):** 기존 `springApplicationJson(...)` 메서드의 `node.put("logging.level.org.hibernate.orm.jdbc.bind", "TRACE");` 바로 **다음 줄**에 H5 BasicBinder 로거를 추가한다(없는 로거는 SUT가 무시 → 버전 무관). 이 메서드는 appService와 보조 서비스가 공유하므로 한 번의 추가로 모든 capture-service에 적용된다:

```java
            node.put("logging.level.org.hibernate.type.descriptor.sql.BasicBinder", "TRACE");
```

`generate()`의 `return YAML.writeValueAsString(root);` **직전**에 보조 서비스 노드 생성을 추가:

```java
            // 보조 capture-service: 로깅 레벨(SAJ) + 인코딩(JTO)만. 에이전트/포트/볼륨은 appService 전용.
            for (String svc : spec.extraLogServices()) {
                if (svc.equals(spec.appService())) {
                    continue;   // appService는 위에서 이미 완전 구성
                }
                ObjectNode extra = services.putObject(svc);
                ObjectNode extraEnvNode = extra.putObject("environment");
                extraEnvNode.put("JAVA_TOOL_OPTIONS", ENCODING_JTO);
                extraEnvNode.put("SPRING_APPLICATION_JSON",
                        springApplicationJson(spec.mybatisNamespaces(), false));
            }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.env.OverrideComposeGeneratorTest' --console=plain`
Expected: PASS (기존 2건 + 신규 1건).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/env/OverrideComposeGenerator.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/env/OverrideComposeGeneratorTest.java
git commit -m "feat(env): OverrideComposeGenerator injects logging/encoding to extra capture services"
```

---

## Task 7: BuildConfig 개명(`sqlCapture`→`traceMode`) + `--trace-mode` 파서

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`(파서 메서드 + 호출부만; dispatch 배선은 Task 8)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliTraceModeTest.java`

**Interfaces:**
- Produces:
  - `BuildConfig.traceMode()` (구 `sqlCapture()` 대체). 컴팩트 생성자 기본값 `"otel"`. 두 편의 생성자의 마지막 위치 인자도 `"otel"` 리터럴 유지.
  - `BuilderCli.traceMode(String value)` (package-private static) — `otel|sleuth|none` 검증, null→`"otel"`, 그 외 throw.

- [ ] **Step 1: Write the failing test**

`BuilderCliTraceModeTest.java`:

```java
package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuilderCliTraceModeTest {

    @Test
    void defaultsToOtelWhenUnset() {
        assertThat(BuilderCli.traceMode(null)).isEqualTo("otel");
    }

    @Test
    void acceptsThreeModes() {
        assertThat(BuilderCli.traceMode("otel")).isEqualTo("otel");
        assertThat(BuilderCli.traceMode("sleuth")).isEqualTo("sleuth");
        assertThat(BuilderCli.traceMode("none")).isEqualTo("none");
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> BuilderCli.traceMode("log"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--trace-mode");
    }

    // parseCsv는 Task 8에서 추가되는 package-private 헬퍼 — 여기서 함께 검증한다.
    @Test
    void parseCsv_handlesNullEmptySingleMultiAndWhitespace() {
        assertThat(BuilderCli.parseCsv(null)).isEmpty();
        assertThat(BuilderCli.parseCsv("")).isEmpty();
        assertThat(BuilderCli.parseCsv("   ")).isEmpty();
        assertThat(BuilderCli.parseCsv("a")).containsExactly("a");
        assertThat(BuilderCli.parseCsv("a,b,c")).containsExactly("a", "b", "c");
        assertThat(BuilderCli.parseCsv(" a , b ")).containsExactly("a", "b");
        assertThat(BuilderCli.parseCsv("a,,b")).containsExactly("a", "b");
    }
}
```

> 주의: `parseCsv` 어서션은 Task 8에서 `parseCsv`가 추가된 뒤에야 컴파일된다. Task 7 단계에서 이 테스트 파일을 만들 때 `parseCsv_*` 메서드는 **Task 8 직후 함께 실행**한다(또는 Task 7에서는 주석 처리했다가 Task 8에서 해제). 권장: Task 7·8을 연속 작성 후 본 테스트 전체를 한 번에 실행.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.BuilderCliTraceModeTest' --console=plain`
Expected: FAIL — `BuilderCli.traceMode` 없음(컴파일 에러).

- [ ] **Step 3: Rename BuildConfig field + add parser**

`BuildConfig.java`: record 컴포넌트 `String sqlCapture` → `String traceMode`. 컴팩트 생성자의 정규화 줄 변경:

```java
        traceMode = traceMode == null ? "otel" : traceMode;   // 기본 otel (sleuth/none은 명시)
```

두 편의 생성자에서 마지막 위치 인자 `"otel"` 리터럴은 그대로(이제 traceMode 위치). 컴포넌트명만 바뀌므로 본문 변경은 컴팩트 생성자 한 줄뿐.

`BuilderCli.java`: 기존 `sqlCaptureMode` 메서드(파일 끝 ~669-678)를 교체:

```java
    /** --trace-mode otel|sleuth|none (미지정 시 기본 otel). 그 외 값은 거부. */
    static String traceMode(String value) {
        if (value == null) {
            return "otel";   // OTEL이 기본. sleuth(레거시 B3 로그상관)/none(로그 byte-offset)은 명시.
        }
        if (!value.equals("otel") && !value.equals("sleuth") && !value.equals("none")) {
            throw new IllegalArgumentException(
                    "--trace-mode must be 'otel', 'sleuth', or 'none', got: " + value);
        }
        return value;
    }
```

`BuildConfig` 생성 호출부(line ~150) 변경:

```java
                traceMode(options.get("--trace-mode")));
```

이 시점에서 `config.sqlCapture()` 를 읽던 다른 지점들(235/311/457 부근)은 아직 옛 이름이라 컴파일 에러가 난다 → **Task 8에서 일괄 갱신**. 따라서 모듈 전체 컴파일/테스트는 Task 8 이후 green. 이 Task는 `BuilderCliTraceModeTest` 만 대상으로 검증한다.

> 컴파일 분리 팁: `BuilderCliTraceModeTest`만 컴파일·실행하려 해도 같은 소스셋이라 `BuilderCli` 전체가 컴파일돼야 한다. 그러므로 이 Task와 Task 8을 **연속으로** 진행하고, 최종 green은 Task 8 Step 4에서 확인한다. 빠른 피드백을 위해 Task 8의 dispatch 갱신을 먼저 적용한 뒤 본 Task의 테스트를 함께 돌려도 된다(권장: Task 7 코드 작성 → Task 8 코드 작성 → 두 Task 테스트 동시 실행).

- [ ] **Step 4: (Task 8과 합류) 컴파일 확인 보류**

Run: `./gradlew :graph-rag-builder:compileJava --console=plain`
Expected: 아직 FAIL 가능(옛 `sqlCapture()` 참조). Task 8에서 해소.

- [ ] **Step 5: Commit (Task 8과 함께 묶어 커밋해도 됨)**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliTraceModeTest.java
git commit -m "refactor(cli): rename sqlCapture->traceMode, add --trace-mode otel|sleuth|none parser"
```

---

## Task 8: BuilderCli 배선 — dispatch(otel|sleuth|none) + capture-services + 모드별 attach 환경

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`
- Test(회귀): `graph-rag-builder/src/test/java/io/graphrag/builder/cli/OtelKafkaBuildAcceptanceTest.java`

**Interfaces:**
- Consumes: `traceMode()`(Task 7), `SleuthLogCapture`/`B3TraceId`(Task 3·4), `AttachedComposeEnvironment.Config.captureServices`(Task 5), `OverrideComposeGenerator.Spec.extraLogServices`(Task 6).
- Produces:
  - `--capture-services a,b,c` 파싱(쉼표 분리, 공백 strip) → `AttachConfig.captureServices`(미지정 시 `List.of()` → Config가 `[appService]`로 정규화).
  - dispatch(explore ~457): `otel`(+receiver)→`OtelSpanCapture`; `sleuth`→`SleuthLogCapture(env.sut(), new B3TraceId(traceRunId, runNonce))`; 그 외(`none`/otel-no-receiver)→`LogParserCapture`.
  - `runAttached`: `sleuth`는 OTLP 리시버·OTEL javaagent 미생성, jto에 인코딩 병합, otelEnv 미주입(빈 맵); `none`은 기존 log 동작(OTEL agent + baggage env); `otel`은 기존 OTLP 경로.

- [ ] **Step 1: Add `--capture-services` to AttachConfig + parsing**

`AttachConfig` record(line ~568)에 `List<String> captureServices` 추가(맨 끝):

```java
    public record AttachConfig(Path userCompose, String appService,
                               int appContainerPort, int appHostPort, int jacocoHostPort,
                               String jdbcUrl, String kafkaBootstrap,
                               String healthPath, int readyTimeoutSeconds,
                               java.util.List<String> captureServices) {}
```

attach 파싱 블록(line ~88-99)의 `new AttachConfig(...)` 끝에 인자 추가:

```java
        AttachConfig attach = options.containsKey("--attach")
                ? new AttachConfig(
                        Path.of(sutComposeStr),
                        required(options, "--app-service"),
                        Integer.parseInt(options.getOrDefault("--app-container-port", "8080")),
                        Integer.parseInt(required(options, "--app-port")),
                        Integer.parseInt(required(options, "--jacoco-port")),
                        required(options, "--jdbc-url"),
                        options.get("--kafka-bootstrap"),
                        options.getOrDefault("--health-path", "/actuator/health"),
                        Integer.parseInt(options.getOrDefault("--ready-timeout", "120")),
                        parseCsv(options.get("--capture-services")))
                : null;
```

CSV 헬퍼 추가(`parseEnvPairs` 옆):

```java
    /** "a,b,c" → [a,b,c] (공백 strip, 빈 토큰 제거). null/빈 → 빈 리스트. (테스트용 package-private) */
    static List<String> parseCsv(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(spec.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
```

- [ ] **Step 2: Update explore() dispatch (line ~455-461)**

`traceRunId` 계산 직후의 `sqlCapture` 생성 블록을 교체:

```java
            String traceRunId = config.commitSha() == null
                    ? config.sutId() : config.sutId() + ":" + config.commitSha();
            String mode = config.traceMode();
            io.graphrag.builder.capture.SqlCaptureBackend sqlCapture;
            if ("otel".equals(mode) && env.otlpReceiver() != null) {
                sqlCapture = new io.graphrag.builder.capture.OtelSpanCapture(env.otlpReceiver(), env.sut(),
                        new io.graphrag.builder.capture.TraceParent(traceRunId));
            } else if ("sleuth".equals(mode)) {
                // per-run nonce(R5): 동일 commit 동시 실행 시 trace 시퀀스 충돌 방지(SecureRandom, 비결정적 OK).
                sqlCapture = new io.graphrag.builder.capture.SleuthLogCapture(env.sut(),
                        new io.graphrag.builder.capture.B3TraceId(traceRunId, newOtlpSecret()));
            } else {
                sqlCapture = new io.graphrag.builder.capture.LogParserCapture(env.sut());
            }
```

(`newOtlpSecret()`는 이미 존재하는 per-run 256-bit hex 생성기 — nonce로 재사용.)

- [ ] **Step 3: Update runAttached() per-mode wiring (line ~304-340)**

`runAttached`의 jto/리시버/otelEnv/override/Config 부분을 모드 분기로 교체. 기존 코드:

```java
        int jacocoContainerPort = 6300;
        String jto = JacocoAgent.containerJavaToolOptions("/grb-agents/jacocoagent.jar", jacocoContainerPort)
                + " -javaagent:/grb-agents/otel-javaagent.jar";

        boolean otelSqlCapture = "otel".equals(config.sqlCapture());
        io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver = null;
        Map<String, String> otelEnv;
        if (otelSqlCapture) {
            ...
        } else {
            otelEnv = otel.env(config.sutId());
        }

        String overrideYaml = new OverrideComposeGenerator().generate(
                new OverrideComposeGenerator.Spec(at.appService(), agentsDir.toAbsolutePath().toString(),
                        at.appContainerPort(), at.appHostPort(), jacocoContainerPort, at.jacocoHostPort(),
                        jto, mybatisLogLevels, otelEnv, otelSqlCapture, otelSqlCapture));
        Path overridePath = workDir.resolve("attach-override.yml");
        Files.writeString(overridePath, overrideYaml);

        var envCfg = new AttachedComposeEnvironment.Config(at.userCompose(), overridePath,
                at.appService(), "grb-attach-" + config.sutId(),
                "http://localhost:" + at.appHostPort(),
                at.jdbcUrl(), config.dbConfig().user(), config.dbConfig().password(),
                "localhost", at.jacocoHostPort(), at.kafkaBootstrap(),
                at.healthPath(), at.readyTimeoutSeconds());
        try (AttachedComposeEnvironment env =
                     new AttachedComposeEnvironment(envCfg, config.dbConfig().type(), otlpReceiver)) {
```

를 다음으로 교체:

```java
        int jacocoContainerPort = 6300;
        String mode = config.traceMode();
        boolean otelSqlCapture = "otel".equals(mode);
        boolean sleuthMode = "sleuth".equals(mode);

        // sleuth: OTEL javaagent 미부착(레거시 brave.Tracing 빈 충돌 회피) + 인코딩 병합. 그 외: 기존대로 otel agent.
        String jacocoJto = JacocoAgent.containerJavaToolOptions("/grb-agents/jacocoagent.jar", jacocoContainerPort);
        String jto = sleuthMode
                ? jacocoJto + " " + OverrideComposeGenerator.ENCODING_JTO
                : jacocoJto + " -javaagent:/grb-agents/otel-javaagent.jar";

        io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver = null;
        Map<String, String> otelEnv;
        if (otelSqlCapture) {
            String secret = newOtlpSecret();
            otlpReceiver = new io.graphrag.builder.capture.otlp.OtlpTraceReceiver();
            otlpReceiver.start("0.0.0.0", secret);
            warnIfHostGatewayUnsupported();
            otelEnv = otel.otlpEnv(config.sutId(), otlpReceiver.hostEndpoint(), secret);
            log.info("OTEL SQL capture (attach): otlp receiver {} (container reaches via {})",
                    otlpReceiver.endpoint(), otlpReceiver.hostEndpoint());
        } else if (sleuthMode) {
            otelEnv = Map.of();   // OTEL agent 미사용 → OTEL_* env 불필요. 상관은 B3 헤더 주입으로.
            log.info("sleuth SQL capture (attach): B3 trace-id log correlation over services {}",
                    effectiveCaptureServices(at));
        } else {
            otelEnv = otel.env(config.sutId());   // none: 기존 log 동작(OTEL env 동등, exporter none + baggage)
        }

        String overrideYaml = new OverrideComposeGenerator().generate(
                new OverrideComposeGenerator.Spec(at.appService(), agentsDir.toAbsolutePath().toString(),
                        at.appContainerPort(), at.appHostPort(), jacocoContainerPort, at.jacocoHostPort(),
                        jto, mybatisLogLevels, otelEnv, otelSqlCapture, otelSqlCapture,
                        effectiveCaptureServices(at)));   // app 포함 목록; generator가 app 노드는 skip
        Path overridePath = workDir.resolve("attach-override.yml");
        Files.writeString(overridePath, overrideYaml);

        var envCfg = new AttachedComposeEnvironment.Config(at.userCompose(), overridePath,
                at.appService(), "grb-attach-" + config.sutId(),
                "http://localhost:" + at.appHostPort(),
                at.jdbcUrl(), config.dbConfig().user(), config.dbConfig().password(),
                "localhost", at.jacocoHostPort(), at.kafkaBootstrap(),
                at.healthPath(), at.readyTimeoutSeconds(),
                effectiveCaptureServices(at));   // app 포함 목록(Config도 빈 목록은 [app]로 정규화)
        try (AttachedComposeEnvironment env =
                     new AttachedComposeEnvironment(envCfg, config.dbConfig().type(), otlpReceiver)) {
```

`runAttached` 옆에 헬퍼 추가(리뷰 item4: `--capture-services`에서 app 누락 시 app 로그 미tail footgun 방지 — app을 항상 포함):

```java
    /** capture-services에 app 서비스를 반드시 포함(누락 시 prepend; 미지정 시 [app]). */
    private static List<String> effectiveCaptureServices(AttachConfig at) {
        List<String> req = at.captureServices();
        if (req.isEmpty()) {
            return List.of(at.appService());
        }
        if (req.contains(at.appService())) {
            return req;
        }
        List<String> out = new java.util.ArrayList<>();
        out.add(at.appService());
        out.addAll(req);
        return out;
    }
```

> 주의: `OverrideComposeGenerator.ENCODING_JTO` 는 Task 6에서 `public static final` 로 추가됨(cross-package 참조). 없으면 Task 6 먼저.
>
> **`--capture-services` 계약**: 지정 시 **app 서비스를 포함한 전체 tail 목록**을 적어야 한다(예: `--capture-services a,b,c`, a=app). app을 빠뜨리면 app 로그가 tail되지 않는다. 미지정 시 `--app-service` 단일. (docs/06·26에 명시 — Task 10.)

- [ ] **Step 4: Update analysis-mode reader (line ~235) + acceptance test comment**

analysis(non-attach) 경로의 `boolean otelSqlCapture = "otel".equals(config.sqlCapture());` (line ~235)를:

```java
                boolean otelSqlCapture = "otel".equals(config.traceMode());
```

개명 잔여 갱신(직접 `BuildConfig(...)` 생성자에 trace-mode 값을 넘기는 테스트들 — CLI 파서를 안 거치므로 `"log"` 가 런타임 검증을 우회한다. 새 spec에서 `log`는 무효 → `none`으로 교체):
- `OtelKafkaBuildAcceptanceTest.java` line 19 주석 `--sql-capture otel` → `--trace-mode otel`(line 53 위치 인자 `"otel"` 는 유효, 유지).
- `BuilderE2eTest.java` line 50: 마지막 인자 `"log"` → `"none"`.
- `BuilderEndpointSelectorTest.java` line 43: 마지막 인자 `"log"` → `"none"`.

- [ ] **Step 5: Compile + run regression (full module)**

Run:
```bash
./gradlew :graph-rag-builder:compileJava :graph-rag-builder:compileTestJava --console=plain
./gradlew :graph-rag-builder:test --console=plain
```
Expected: BUILD SUCCESSFUL, 전체 테스트 green(Task 1-7 신규 포함, 기존 회귀 포함). 실패 시 잔여 `sqlCapture`/`Config`/`Spec` arity 불일치를 grep으로 찾아 수정:
`grep -rn "sqlCapture\|\.captureServices()" graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`

- [ ] **Step 6: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/OtelKafkaBuildAcceptanceTest.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderE2eTest.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderEndpointSelectorTest.java
git commit -m "feat(cli): wire trace-mode dispatch (otel|sleuth|none) and --capture-services"
```

---

## Task 9: EndpointExplorationRunner — 상관 헤더 우선순위(traceparent + B3 제거)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/CorrelationHeaderTest.java`

**Interfaces:**
- Produces:
  - `static java.util.LinkedHashMap<String,String> applyCorrelationPriority(Map<String,String> userHeaders, Map<String,String> scopeHeaders)` (package-private) — 사용자 헤더에서 알려진 상관 헤더(`traceparent`, `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-Sampled`, `b3`)를 **case-insensitive 제거**한 뒤, scope 헤더를 덮어쓴다. backend 상관 헤더 우선(중복·비결정 전파 방지).
- `doSend`는 사용자 헤더+scope 헤더 주입 시 이 메서드를 사용.

현재 `doSend`는 `traceparent`만 skip한다 → sleuth의 B3 헤더도 사용자 제공분과 충돌할 수 있어 전체 상관 헤더 셋으로 확장한다(스펙 §7-3, Gemini I1·GPT I3).

- [ ] **Step 1: Write the failing test**

`CorrelationHeaderTest.java`:

```java
package io.graphrag.builder.run;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationHeaderTest {

    @Test
    void stripsUserCorrelationHeaders_caseInsensitive_thenOverlaysScope() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("X-Custom", "keep");
        user.put("TraceParent", "00-usertrace-userspan-01");
        user.put("x-b3-traceid", "userb3");
        user.put("b3", "user-b3-1");

        Map<String, String> scope = Map.of(
                "X-B3-TraceId", "backendtrace",
                "X-B3-SpanId", "backendspan",
                "X-B3-Sampled", "1",
                "b3", "backendtrace-backendspan-1");

        Map<String, String> merged = EndpointExplorationRunner.applyCorrelationPriority(user, scope);

        assertThat(merged).containsEntry("X-Custom", "keep");                 // 비상관 헤더 유지
        assertThat(merged).containsEntry("X-B3-TraceId", "backendtrace");     // backend 우선
        assertThat(merged).containsEntry("b3", "backendtrace-backendspan-1");
        // 사용자 traceparent/대소문자 변형 b3는 제거됨(중복 전파 방지)
        assertThat(merged).doesNotContainKey("TraceParent");
        assertThat(merged).doesNotContainKey("traceparent");
        assertThat(merged.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("traceparent"))).isTrue();
    }

    @Test
    void noScopeHeaders_keepsUserNonCorrelationOnly() {
        Map<String, String> user = Map.of("Authorization", "Bearer x", "traceparent", "00-a-b-01");
        Map<String, String> merged = EndpointExplorationRunner.applyCorrelationPriority(user, Map.of());
        assertThat(merged).containsEntry("Authorization", "Bearer x");
        assertThat(merged.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("traceparent"))).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.CorrelationHeaderTest' --console=plain`
Expected: FAIL — `applyCorrelationPriority` 없음(컴파일 에러).

- [ ] **Step 3: Add the pure helper + use it in doSend**

`EndpointExplorationRunner.java`에 상수 + 메서드 추가(클래스 본문, doSend 근처):

```java
    /** trace-mode가 주입하는 상관 헤더 이름들(case-insensitive). backend 값이 사용자 값을 이긴다. */
    private static final java.util.Set<String> CORRELATION_HEADERS = java.util.Set.of(
            "traceparent", "x-b3-traceid", "x-b3-spanid", "x-b3-sampled", "b3");

    /** 사용자 헤더에서 상관 헤더를 case-insensitive 제거 후 scope 상관 헤더를 덮어쓴다. */
    static java.util.LinkedHashMap<String, String> applyCorrelationPriority(
            Map<String, String> userHeaders, Map<String, String> scopeHeaders) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> h : userHeaders.entrySet()) {
            if (!CORRELATION_HEADERS.contains(h.getKey().toLowerCase(java.util.Locale.ROOT))) {
                out.put(h.getKey(), h.getValue());
            }
        }
        out.putAll(scopeHeaders);
        return out;
    }
```

`doSend`의 사용자 헤더 루프 + scope 주입(line ~670-682)을 교체. 기존:

```java
        for (Map.Entry<String, String> h : extraHeaders.resolved(Instant.now()).entrySet()) {
            if (h.getKey().equalsIgnoreCase("traceparent")) {
                log.warn("ignoring user-supplied 'traceparent' header (backend correlation header wins)");
                continue;
            }
            builder.header(h.getKey(), h.getValue());
        }
        // SQL 캡처 backend의 상관 헤더 주입 (OTEL: traceparent, log-parser: 없음).
        for (Map.Entry<String, String> h : sqlScope.requestHeaders().entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
```

교체 후:

```java
        // 상관 헤더는 활성 trace-mode가 결정(otel: traceparent, sleuth: B3, none: 없음).
        // 사용자 제공 상관 헤더는 제거하고 backend 것만 주입(중복·비결정 전파 방지).
        Map<String, String> userHeaders = extraHeaders.resolved(Instant.now());
        Map<String, String> scopeHeaders = sqlScope.requestHeaders();
        for (String name : userHeaders.keySet()) {
            if (CORRELATION_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))
                    && !scopeHeaders.isEmpty()) {
                log.warn("ignoring user-supplied correlation header '{}' (backend wins)", name);
            }
        }
        for (Map.Entry<String, String> h : applyCorrelationPriority(userHeaders, scopeHeaders).entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.CorrelationHeaderTest' --console=plain
./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.EndpointExplorationRunnerUrlTest' --console=plain
```
Expected: PASS (신규 2건 + 기존 runner 회귀 green).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/run/CorrelationHeaderTest.java
git commit -m "feat(run): strip all user correlation headers (traceparent+B3) before backend injection"
```

---

## Task 10: 문서/스크립트 개명 블래스트 반경

**Files (수정):**
- `README.md`, `docs/00-getting-started.md`, `docs/03-graph-rag-builder.md`, `docs/06-test-environment.md`, `docs/26-attach-mode.md`, `docs/27-roadmap-otel-capture-stub-seeding.md`, `e2e/run-attach-otel-e2e.sh`

**원칙:** 사용자 향(現행) 표기만 갱신. `--sql-capture` → `--trace-mode`, 값 `log` → `none`, 그리고 새 `sleuth` 모드 + `--capture-services` 를 문서화. 과거 plan/report/old-spec/archive 문서는 미수정(Global Constraints).

- [ ] **Step 1: 정확한 참조 위치 재확인**

Run: `grep -rn "sql-capture\|sqlCapture\|--capture-services\|trace-mode" README.md docs/00-getting-started.md docs/03-graph-rag-builder.md docs/06-test-environment.md docs/26-attach-mode.md docs/27-roadmap-otel-capture-stub-seeding.md e2e/run-attach-otel-e2e.sh`

- [ ] **Step 2: docs/06-test-environment.md — 모드 섹션 개정**

`## SQL 캡처 모드 (`--sql-capture`)`(line ~86) 제목을 `## trace 모드 (`--trace-mode`)` 로 바꾸고, 본문을 3-모드 표로 갱신. 스펙 §2 표를 그대로 옮긴다:

```markdown
## trace 모드 (`--trace-mode`)

빌더(도구 1)가 요청별 SQL·바인딩을 어떤 trace/상관 백엔드로 수집하는지 고른다. `--trace-mode <otel|sleuth|none>`(기본 `otel`). 공통 목표는 **API↔SQL 매핑 그래프**다.

| 모드 | trace/baggage 전파 | SQL 추출 | 매핑 범위 | 상관 헤더 |
|---|---|---|---|---|
| `none` | 없음 | 로그 byte-offset(직렬) | 동기·동일프로세스(모놀리식 baseline) | 없음 |
| `sleuth` | Sleuth/B3(+baggage) | 로그 trace-id 상관 | + 비동기 서비스간(B→C) | B3 |
| `otel` | OTEL agent(traceparent+baggage) | OTLP DB span(로그 fallback) | + 비동기 서비스간(B→C) | traceparent |

- `sleuth`(레거시 Java8+Sleuth+Eventuate/Tram): 요청별 B3 trace-id를 A에 주입하고 그 trace-id가 박힌 로그 라인만 상관해 A→B→C SQL을 회수한다. OTEL javaagent를 부착하지 않는다(레거시 `brave.Tracing` 빈 충돌 회피). **전제**: SUT logback이 `%X{traceId}`(또는 동등 MDC 키)를 출력해야 한다(SUT 제공자 책임).
- `none`: 추적 전무 SUT의 격하 baseline(직렬·격리 없음). 구 `--sql-capture log` 와 동등.
- 멀티서비스 로그 수집: attach 모드에서 `--capture-services a,b,c` 로 여러 컨테이너 로그를 한 파일에 인터리브 tail한다(비동기 B→C 캡처). 미지정 시 `--app-service` 단일.
```

- [ ] **Step 3: 나머지 문서/스크립트 치환**

- `README.md` line ~155: `--sql-capture log` → `--trace-mode none`.
- `docs/00-getting-started.md` line ~121: 표의 `--sql-capture log` 행을 `--trace-mode none`(+ 한 줄 `--trace-mode sleuth` 추가)로 갱신.
- `docs/03-graph-rag-builder.md` line ~58: `--sql-capture log` → `--trace-mode none` (그리고 sleuth가 로그 파싱+trace-id 상관임을 한 줄 추가).
- `docs/26-attach-mode.md` line ~63,64,80: `--sql-capture log` → `--trace-mode none`, `--sql-capture otel` → `--trace-mode otel`. `sleuth` 모드 + `--capture-services` 한 단락 추가(B3 주입·로그 trace-id 상관·OTEL agent 미부착·logback `%X{traceId}` 전제).
- `docs/27-roadmap-otel-capture-stub-seeding.md` line ~16: `--sql-capture log` → `--trace-mode none`(상태 문단 표기만).
- `e2e/run-attach-otel-e2e.sh` line ~20,30: `--sql-capture otel` → `--trace-mode otel`.

- [ ] **Step 4: 깨진 참조 없는지 확인**

Run: `grep -rn "sql-capture" README.md docs/00-getting-started.md docs/03-graph-rag-builder.md docs/06-test-environment.md docs/26-attach-mode.md docs/27-roadmap-otel-capture-stub-seeding.md e2e/run-attach-otel-e2e.sh`
Expected: 출력 없음(현행 문서에서 옛 표기 제거 완료). 과거 plan/report/spec/archive는 의도적으로 잔존.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/00-getting-started.md docs/03-graph-rag-builder.md \
        docs/06-test-environment.md docs/26-attach-mode.md \
        docs/27-roadmap-otel-capture-stub-seeding.md e2e/run-attach-otel-e2e.sh
git commit -m "docs: rename --sql-capture->--trace-mode, document sleuth mode + --capture-services"
```

---

## Task 11: 전체 회귀 + 자기검토(DoD 확인)

**Files:** 없음(검증 전용).

- [ ] **Step 1: 모듈 전체 테스트 green**

Run: `./gradlew :graph-rag-builder:test --console=plain`
Expected: BUILD SUCCESSFUL. 신규(SqlLogParser H5/traceId, B3TraceId, SleuthLogCapture, BuilderCliTraceMode, CorrelationHeader, Attached/Override 다중) + 기존 회귀 전부 green.

- [ ] **Step 2: 잔여 옛 식별자/값 스캔**

Run:
```bash
grep -rn "sqlCaptureMode\|config\.sqlCapture\|\"--sql-capture\"" graph-rag-builder/src
grep -rn '"log"' graph-rag-builder/src/test/java/io/graphrag/builder/cli/
```
Expected: 둘 다 출력 없음. (식별자 grep은 옛 메서드/접근자/플래그를, 값 grep은 BuildConfig에 직접 넘기던 stale `"log"` 리터럴을 잡는다. `SqlCaptureBackend` **타입명**은 유지 대상이라 검색에서 제외.)

- [ ] **Step 3: DoD 대비 자기검토**

스펙 §10 DoD 체크:
- 단위(SqlLogParser): H5 축약/풀·H6·MyBatis 자동감지 ✔(Task 1, 기존), traceId 추출 MDC/Sleuth·64/128bit·대소문자·hex 오탐 방지 ✔(Task 2).
- 단위(B3TraceId): B3 포맷·위임 결정성·nonce 유일성 ✔(Task 3).
- 단위(헤더 우선순위): 사용자 B3/traceparent 제거 후 backend만 ✔(Task 9).
- 통합(SleuthLogCapture): traceId 일치만 순서 보존·인프라/타 요청 배제(Task 4 test 1), **quiescence 창 내 늦게 온 bind까지 대기**(Task 4 test 2, 스펙 §10 Sonnet I8), SQL 없는 요청 조기 빈 결과(Task 4 test 3) ✔.
- 통합(멀티서비스 logsCommand): `--capture-services a,b,c` 다중 tail ✔(Task 5).
- 회귀(개명): `--trace-mode otel`≡구 otel, `none`≡구 log, 잘못된 값 거부 ✔(Task 7·8).
- 수용(라이브): **Spec 1 게이트로 보류**(이 계획 범위 밖) — 문서에 명시.

누락 발견 시 해당 Task로 돌아가 보강.

- [ ] **Step 4: (선택) finishing-a-development-branch 준비**

PR 전 게이트(사용자 CLAUDE.md): 스펙-준수 리뷰 + 코드품질 리뷰, 회귀 green(Step 1), 문서 갱신(Task 10) — 모두 충족 후 `superpowers:finishing-a-development-branch`.

---

## Self-Review (작성자 점검 결과)

1. **Spec coverage:** §5 멀티서비스 수집→T5/T6/T8, §6 컴포넌트(B3TraceId/SleuthLogCapture/SqlLogParser/Attached/Override/BuilderCli/Runner)→T1-T9, §7 헤더 우선순위→T9, §8 ORM 자동감지+traceId 추출→T1/T2, §9 await/quiescence/타임아웃/인코딩→T4(타임아웃)·T6/T8(인코딩), §10 테스트→각 Task + T11, 개명→T7/T8/T10. `AnalysisEnvironment`는 스펙대로 수정 제외(분석-모드 reader 한 줄만 T8 Step4). 라이브 수용은 Spec 1로 보류 명시.
2. **Placeholder scan:** 모든 코드 스텝에 실제 코드 포함. TBD/임의 처리 없음.
3. **Type consistency:** `traceMode()`(T7) ↔ 사용(T8); `B3TraceId(runId, nonce)`/`Ids.headers()`(T3) ↔ SleuthLogCapture(T4)·BuilderCli(T8); `Config.captureServices`(T5, 13-arg 호환 생성자 포함) ↔ BuilderCli `at.captureServices()`(T8); `Spec.extraLogServices`+`public ENCODING_JTO`(T6) ↔ BuilderCli(T8); `extractTraceId`/`traceIdMatches`(T2) ↔ SleuthLogCapture(T4); `applyCorrelationPriority`/`CORRELATION_HEADERS`(T9) 일관. **컴파일 green 불변식**: 각 구조 변경 Task(T5 Config·T6 Spec·T7 BuildConfig)는 호환 생성자/기계적 개명으로 자기 완결 컴파일을 유지(GPT I3) — 단일 테스트 실행도 main source 전체를 컴파일하므로 필수.

---

## 3-Model 리뷰 반영 기록 (2026-06-18)

스펙·계획은 CLAUDE.md 규칙에 따라 3-model 교차 리뷰(Claude Sonnet ×2[Gemini 슬롯 폴백 포함] + OpenAI GPT-5.5)를 거쳤다. 판정·반영:

- **수용(critical)** — `ENCODING_JTO` `public` 누락(cross-package 컴파일 실패) → T6; H5 `BasicBinder=TRACE` 로그레벨 주입 누락(파서 dead code) → T6 `springApplicationJson`+테스트.
- **수용(important)** — stale `"log"` 리터럴 테스트 2건(BuilderE2eTest/BuilderEndpointSelectorTest) → T8 Step4 + T10/T11 스캔; `SLEUTH_BRACKET` hex spanId vs 픽스처 `"sp"` → T4 픽스처 hex화; T5 Config arity가 컴파일 깸 → 13-arg 호환 생성자; `drain(timeout)`가 caller timeout 무시 → `firstMatchDeadline=min(...)`; quiescence late-arrival 테스트 누락(§10) → T4 신규 테스트; `parseCsv` 무테스트 → T7/T8 테스트.
- **수용(clarity)** — Spec 호환 생성자 주석 arity(9/11) 정정; O(n²) 폴링 스캔 주석; `captureServices(at)` 중복 헬퍼 제거.
- **반려** — GPT I5(compose project name `grb-attach-<sutId>` 동시실행 충돌): sleuth가 도입한 결함이 아니라 **기존 attach 인프라의 선재 동작**이며, 스펙 R5의 nonce는 trace-id 상관 정확성에 한정된다. compose/볼륨 격리는 별개의 선재 이슈로 본 spec 범위 밖 — **알려진 제약(동일 sutId 동시 attach 미지원)으로 문서화**하고 별도 추적. (SutProcess.springApplicationJson의 H5 주입도 비-attach(jar) 경로라 스펙 §13 out-of-scope로 보류 — 분석 모드 H5 SUT 필요 시 후속.)
