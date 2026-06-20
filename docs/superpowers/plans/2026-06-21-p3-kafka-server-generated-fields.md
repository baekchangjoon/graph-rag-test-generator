# P3 — Kafka 서버-생성 필드 스트리핑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** 생성된 Kafka 검증 테스트가 서버-생성 필드(`eventId` UUID·`occurredAt` ISO-8601)를 리터럴로 하드코딩해 재실행 시 격리되는 문제(G3)를 없앤다 — 서버-생성 필드는 **패턴 매처**로, 입력 유래 필드는 **구체값**으로 단언한다.

**Architecture:** HTTP 응답 경로엔 이미 `FixtureComposer.looksServerGenerated`(UUID_RE/TIMESTAMP_RE)가 있으나 Kafka payload 경로(`Generator.deterministicPayload`)만 parity에서 빠져 있다. ① detector를 공유 util로 추출(REQ-009), ② `deterministicPayload`가 payload 필드를 3분류(입력유래=보존/DB-PK=제거/서버생성=패턴단언 등록)하도록 변경(REQ-009/010), ③ `test-class.mustache` kafkaEmits 블록이 JSONAssert `CustomComparator`로 서버-생성 필드를 per-field 패턴 매칭하도록 확장(REQ-011), ④(Should) 캡처-2회 diff로 휴리스틱 거짓양성 보완(REQ-012).

**Tech Stack:** Java 17, Jackson, JSONAssert(`org.skyscreamer.jsonassert`, 이미 의존), Mustache, JUnit5+AssertJ.

**REQ:** REQ-009(parity 분류)·REQ-010(입력유래 보존)·REQ-011(패턴 매처+템플릿) = Must; REQ-012(2회 diff) = Should.

> 출처: `docs/2026-06-20-method1-tainted-spring-tool-gaps.md`(§5 P3), 요구사항 `docs/superpowers/requirements/2026-06-20-method1-tool-gaps-requirements.md`(REQ-009~012).

## 현황(실코드, off main 489c6d9)
- `Generator.deterministicPayload`(L463): payload ObjectNode에서 값이 `substitutions`(입력유래) 또는 `nonDeterministicValues`(DB PK)에 든 필드를 **제거**하고 나머지를 `payloadJson` 문자열로 반환. 서버-생성 `eventId`/`occurredAt`은 둘 다 아니라 **리터럴로 남음** → 재실행 불일치(G3).
- `buildScenarioMethod`(L182~192): `modelEmit.put("payloadJson", jsonEscape(deterministicPayload(emit.payload(), fixture)))`. `emitKeyExpr`(L443~457)는 key가 substitution이면 변수, nonDeterministic이면 `"null"`, else 리터럴.
- 템플릿 `test-class.mustache` kafkaEmits(L45~52): `JSONAssert.assertEquals("{{{payloadJson}}}", record.value(), false)` (LENIENT — 제거된 필드는 비교 안 함, but 남은 리터럴은 strict equals).
- `FixtureComposer`: `looksServerGenerated`(L280, `private static`), `UUID_RE`(L274), `TIMESTAMP_RE`(L276).

---

## Task 1: `ServerGeneratedDetector` 공유 util 추출 (REQ-009)

**REQ-IDs:** REQ-009

**Files:**
- Create: `test-generator/src/main/java/io/graphrag/generator/compose/ServerGeneratedDetector.java`
- Modify: `test-generator/src/main/java/io/graphrag/generator/compose/FixtureComposer.java` (delegate)
- Test: `test-generator/src/test/java/io/graphrag/generator/compose/ServerGeneratedDetectorTest.java`

- [ ] **Step 1 — 실패 테스트**
```java
package io.graphrag.generator.compose;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ServerGeneratedDetectorTest {
    @Test
    void detects_uuid_and_iso8601_andClassifiesPattern() {
        assertThat(ServerGeneratedDetector.looksServerGenerated("3f2504e0-4f89-41d3-9a0c-0305e82c3301")).isTrue();
        assertThat(ServerGeneratedDetector.looksServerGenerated("2026-06-21T10:15:30Z")).isTrue();
        assertThat(ServerGeneratedDetector.looksServerGenerated("hello")).isFalse();
        assertThat(ServerGeneratedDetector.patternType("3f2504e0-4f89-41d3-9a0c-0305e82c3301")).isEqualTo("UUID");
        assertThat(ServerGeneratedDetector.patternType("2026-06-21T10:15:30Z")).isEqualTo("TIMESTAMP");
        assertThat(ServerGeneratedDetector.patternType("hello")).isNull();
    }
}
```

- [ ] **Step 2 — 실패 확인**: `./gradlew :test-generator:test --tests '*ServerGeneratedDetectorTest' -q` → FAIL(클래스 없음).

- [ ] **Step 3 — 구현**: `FixtureComposer`의 `UUID_RE`/`TIMESTAMP_RE`/`looksServerGenerated`를 그대로 `ServerGeneratedDetector`로 옮긴다(regex 변경 금지 — HTTP 경로 회귀 0). `public final class ServerGeneratedDetector`에:
```java
public static boolean looksServerGenerated(String value) {
    return UUID_RE.matcher(value).matches() || TIMESTAMP_RE.matcher(value).matches();
}
/** 매칭 패턴 종류: "UUID" | "TIMESTAMP" | null. */
public static String patternType(String value) {
    if (UUID_RE.matcher(value).matches()) return "UUID";
    if (TIMESTAMP_RE.matcher(value).matches()) return "TIMESTAMP";
    return null;
}
/** 생성 코드가 import할 패턴 정규식 리터럴(Java 문자열). */
public static String regexFor(String patternType) {
    return "UUID".equals(patternType) ? UUID_REGEX : TIMESTAMP_REGEX;  // 동일 패턴 문자열 상수
}
```
`FixtureComposer.looksServerGenerated`는 `ServerGeneratedDetector.looksServerGenerated`로 위임(또는 호출부 직접 교체) — 기존 private 메서드/필드 제거, 회귀 0. `UUID_REGEX`/`TIMESTAMP_REGEX`는 `regexFor`가 생성 테스트에 심을 정규식 문자열(매처 객체가 아니라 String).

- [ ] **Step 4 — 통과 + FixtureComposer 회귀**: `./gradlew :test-generator:test --tests '*ServerGeneratedDetectorTest' --tests '*FixtureComposer*' -q` → PASS.

- [ ] **Step 5 — Commit**:
```bash
git add test-generator/src/main/java/io/graphrag/generator/compose/ServerGeneratedDetector.java \
        test-generator/src/main/java/io/graphrag/generator/compose/FixtureComposer.java \
        test-generator/src/test/java/io/graphrag/generator/compose/ServerGeneratedDetectorTest.java
git commit -m "refactor(gen): extract ServerGeneratedDetector (UUID/ISO-8601) for HTTP↔Kafka parity (REQ-009)"
```

---

## Task 2: `deterministicPayload` 3분류 — 서버생성 패턴 등록 + 입력유래 보존 (REQ-009/010)

**REQ-IDs:** REQ-009, REQ-010

**Files:**
- Modify: `test-generator/src/main/java/io/graphrag/generator/Generator.java` (`deterministicPayload` → payload 분류 + emit 모델에 `serverGeneratedFields` 추가)
- Test: `test-generator/src/test/java/io/graphrag/generator/GeneratorKafkaServerFieldsTest.java`

**설계(분류 규칙, payload 각 textual 필드 v):**
1. `substitutions.containsKey(v)` → **입력 유래**: 제거하지 않고 그대로 남긴다(구체값 단언; REQ-010). [현 코드는 제거 → 변경]
2. `nonDeterministicValues.contains(v)` → **DB PK**: 제거(현 동작 유지).
3. 위 둘 다 아니고 `ServerGeneratedDetector.looksServerGenerated(v)` → **서버 생성**: payload에는 남기되, `(fieldName, patternType)`를 `serverGeneratedFields`로 수집해 Task 3에서 per-field 패턴 매처로 단언(REQ-011). 리터럴 비교 대상에서 제외되도록 Customization으로 처리.
4. 그 외 → 결정적 필드: 그대로 남겨 리터럴 equals.

`deterministicPayload`의 반환을 (payloadJson 문자열, `List<ServerGeneratedField>`) 쌍으로 바꾸거나, emit 모델을 채우는 호출부(L190 인근)에서 분류 결과를 `modelEmit.put("serverGeneratedFields", ...)`로 함께 넣는다. `ServerGeneratedField` = `{ field: String, regex: String }`(regex는 `ServerGeneratedDetector.regexFor(patternType)`).

- [ ] **Step 1 — 실패 테스트**: `GeneratorKafkaServerFieldsTest`에서 (a) 서버생성 `eventId`(UUID)·`occurredAt`(ISO-8601)를 실은 Kafka emit fixture로 Generator를 돌려, 생성 소스에 리터럴 UUID/timestamp가 **단언 리터럴로 박히지 않고** per-field 패턴 매칭(또는 serverGeneratedFields 모델 채워짐)을 확인; (b) 입력 유래 필드(substitutions 값)는 payloadJson에 **남아 있음**(구체값 단언). 기존 `GeneratorTest`/`GeneratorCollectionBodyTest`의 kafka emit 테스트 패턴을 참조해 fixture 구성(`ComposedFixture` + emit).
  - 구현자 메모: 정확한 fixture 구성은 기존 kafka emit 테스트(`grep -n "emit\|kafkaEmit\|CapturedEventEmit\|ComposedFixture" test-generator/src/test/java/io/graphrag/generator/*.java`)에서 가장 가까운 예를 복제. substitutions/nonDeterministicValues는 `ComposedFixture` 생성자 인자.

- [ ] **Step 2 — 실패 확인** → **Step 3 — 구현**(위 분류 규칙) → **Step 4 — 통과**. `./gradlew :test-generator:test --tests '*GeneratorKafkaServerFields*' -q`.
  - **확인 필요(구현 시):** REQ-010의 "입력 유래 보존"이 현재 제거 동작과 충돌 — 입력 유래 필드를 payloadJson에 남기면, 생성 테스트가 보내는 happy 입력과 캡처 입력이 다를 때 리터럴 불일치 가능. emit **key**는 `emitKeyExpr`가 substitution을 **변수**로 emit한다(L443~457). payload의 입력 유래 필드도 동일 의미가 필요하면, JSONAssert Customization으로 "actual == 그 입력 변수"를 단언(Task 3). 우선 구현은: 입력 유래 필드를 `substitutionFields`(field→변수)로 수집해 Task 3에서 변수 비교 Customization 생성. (이 결정은 권장안 — 캡처-유래 리터럴 박제보다 입력 변수 대조가 정확.)

- [ ] **Step 5 — Commit**: `feat(gen): classify Kafka payload fields (input-derived/PK/server-generated) (REQ-009,010)`

---

## Task 3: 템플릿 — JSONAssert Customization per-field 패턴/변수 단언 (REQ-011)

**REQ-IDs:** REQ-011

**Files:**
- Modify: `test-generator/src/main/resources/templates/test-class.mustache` (kafkaEmits 블록)
- Modify: `test-generator/.../Generator.java` (kafkaEmits 모델에 `serverGeneratedFields`/`substitutionFields` 슬롯 + import 헬퍼)
- Test: `test-generator/.../GeneratorKafkaServerFieldsTest.java`(생성 소스가 컴파일·패턴 단언 포함)

**설계:** kafkaEmits 블록의 단일 `JSONAssert.assertEquals(payloadJson, record.value(), false)`를 `CustomComparator` 기반으로 교체:
```
org.skyscreamer.jsonassert.JSONAssert.assertEquals("{{{payloadJson}}}", record.value(),
    new org.skyscreamer.jsonassert.comparator.CustomComparator(
        org.skyscreamer.jsonassert.JSONCompareMode.LENIENT
        {{#serverGeneratedFields}}, new org.skyscreamer.jsonassert.Customization("{{field}}",
            (o1, o2) -> o2 != null && o2.toString().matches("{{{regex}}}")){{/serverGeneratedFields}}
        {{#substitutionFields}}, new org.skyscreamer.jsonassert.Customization("{{field}}",
            (o1, o2) -> java.util.Objects.equals(o2 == null ? null : o2.toString(), String.valueOf({{{var}}}))){{/substitutionFields}}
    ));
```
serverGeneratedFields/substitutionFields가 없으면 빈 customization 목록 → 기존 LENIENT 동작과 동일(회귀 0). `{{{regex}}}`는 Java 문자열 escape된 정규식.

- [ ] Step 1 실패 테스트(생성 소스에 `Customization("eventId", … matches(UUID_REGEX))` 포함 + `javax.tools.JavaCompiler`로 컴파일 통과 — `GeneratorCliTest`류 컴파일 검증 패턴 재사용) → Step 2 실패확인 → Step 3 템플릿+모델 구현 → Step 4 통과 → Step 5 commit `feat(gen): per-field pattern/variable assertions for Kafka server-generated fields (REQ-011)`.
  - **확인 필요:** Mustache가 모델의 `serverGeneratedFields`(List<Map>)를 순회하도록 `ScenarioMethod`/`modelEmit`에 슬롯 추가. 생성 소스 정규식 escape는 `jsonEscape`/`Generator`의 기존 escape 헬퍼 재사용.

---

## Task 4: (Should) 캡처-2회 diff — 쓰기 경로 INSERT 역연산 정리 (REQ-012)

**REQ-IDs:** REQ-012

> 별도 증분(별 PR 가능). builder 측 dual-invoke + `deleteSeeds`/`Seeds.delete` 역연산 정리. Touch: `EndpointExplorationRunner`(dual-invoke + traceId pairing), `KafkaCaptureReceiver`(2차 drain), 결과 `ComposedFixture.nonDeterministicValues` 기록. SUT 아웃-오브-프로세스라 러너 JDBC 롤백 불가 — 1차 발행 행을 캡처 INSERT의 역(DELETE)으로 정리 후 2차 발행. 역연산 불가 부작용은 휴리스틱(Task 1~3)만 사용.

- [ ] (상세 TDD는 Task 1~3 머지 후, builder 캡처 코드 재그라운딩하여 확정한다 — 이 task는 Should이며 G3 핵심(휴리스틱)은 Task 1~3로 닫힌다.)

---

## Task 5: 회귀 + 매트릭스 (REQ-009~011 🟢)

- [ ] `./gradlew :test-generator:test :graph-rag-builder:test -q` BUILD SUCCESSFUL + `./e2e/run-e2e.sh` green(0 failures). 요구사항 매트릭스 REQ-009/010/011 🟢(REQ-012는 Task 4 완료 시), Coverage 갱신. Commit.

---

## Self-Review(작성자)
- Spec coverage: REQ-009(T1,T2)·REQ-010(T2,T3)·REQ-011(T3)·REQ-012(T4). 매핑 누락 없음.
- 핵심 설계 결정(권장안): 서버-생성=JSONAssert Customization 패턴 매처, 입력유래=Customization 변수 대조(emitKeyExpr 동형), DB-PK=기존 제거. 회귀 안전장치: customization 없으면 기존 LENIENT 동작.
- **확인 필요(구현 시 grounding):** ① 기존 kafka emit 테스트 fixture 구성(ComposedFixture 인자) ② ScenarioMethod/modelEmit 슬롯 추가 위치 ③ JSONAssert Customization API 정확 시그니처 ④ REQ-010 입력유래 보존의 정확 의미(변수 대조 vs 리터럴) — Task 2에서 확정.

## Execution
Subagent-driven: task별 spec+quality 리뷰. P2~P5 진행 패턴과 동일.
