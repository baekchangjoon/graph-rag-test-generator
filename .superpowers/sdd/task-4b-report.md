# Task 4b Report — V3(a) rev.4 partition 등가 게이트 + PjacocoOtelScopeClient (REQ-004)

- 완료일: 2026-06-23
- 담당: Claude Code (task-4b agent)
- REQ: REQ-004 (rev.4 — partition 등가, OTel-scope/traceId 경로)

---

## 결론 (한 줄)

**PARTITION MATCH — V3(a) rev.4 PASS.** vanilla와 OTel-scope 두 벡터 모두 partition `{{0,2},{1},{3}}`로 일치.

---

## 방법론

### 게이트 재정의 (rev.4)

design §5.1에 따라 "cross-vector 키 동일성" → "partition 등가"로 재정의.
- 절대 키 동일성: 요구하지 않음(OTel-scope는 JPA·async 추가 귀속으로 vanilla와 키 다름)
- 요구 속성: run 내부 일관성 — 같은 arm→같은 키, 다른 arm→다른 키 (= partition 보존)

### canonical 경로: OTel-scope/traceId

baggage `test.id` 경로(task-4)는 pre-servlet `JwtAuthenticationFilter` 4개 probe를 drop
(`incompleteAttribution=true`). OTel-scope/traceId 경로(commit `71e4657`)는 OTel javaagent가
filter chain 전체를 span으로 감싸 `ThreadLocalContextStorage#attach`가 JwtFilter 실행 전에
호출되므로 해당 probe를 정상 캡처(`incompleteAttribution=false`, droppedProbes=0).

### 두 벡터

**VANILLA 벡터:**
- petclinic + JaCoCo tcpserver(`output=tcpserver`)
- 요청마다 `CoverageClient.dump(reset=true)` → delta `ExecutionDataStore` → `CoverageFingerprint.of`

**OTel-scope 벡터 (PjacocoOtelScopeClient):**
- petclinic + OTel javaagent FIRST + pjacoco(`traceKeyAutoCreate=true`)
- 요청마다 고유 W3C traceparent(`00-<traceId>-0000000000000001-01`) 헤더 전송
- 응답 후 `POST /__coverage__/test/stop?testId=<traceId>&result=passed` flush
- `<traceId>.exec` 수신 후 `ExecFileLoader.load` → `CoverageFingerprint.of`

**입력 시퀀스 (동일):**

| req | lastName= | 예상 arm |
|-----|-----------|---------|
| 0 | `` (empty) | results-found |
| 1 | `ZZZNONE` | not-found |
| 2 | `Davis` | results-found |
| 3 | `Franklin` | results-found |

### partition 비교 방법

각 벡터의 req index → coverageKey 매핑에서 같은 키를 공유하는 인덱스를 그룹화.
`Set<Set<Integer>> toPartition(Map<Integer,String>)` → `equals()` 비교.
불일치 시 `fail(...)` → V3(a) rev.4 FAIL (정직 판정).

---

## 실제 측정값

### Vanilla 벡터

| req | lastName= | coverageKey |
|-----|-----------|-------------|
| 0 | `` | `35763958eb8eef2d` |
| 1 | `ZZZNONE` | `e1859cc39e870bce` |
| 2 | `Davis` | `35763958eb8eef2d` |
| 3 | `Franklin` | `3a35ec74ec7027b` |

**vanilla partition = `{{0,2},{1},{3}}`** (distinct paths=3)

### OTel-scope 벡터

| req | lastName= | traceId (prefix) | coverageKey | exec bytes |
|-----|-----------|-----------------|-------------|-----------|
| 0 | `` | `0000000000000000…` | `b13e082e8378dc20` | 607 |
| 1 | `ZZZNONE` | `0000000000000001…` | `7650f252052ff381` | 415 |
| 2 | `Davis` | `0000000000000002…` | `b13e082e8378dc20` | 607 |
| 3 | `Franklin` | `0000000000000003…` | `5e4b01494e276852` | 607 |

**OTel-scope partition = `{{0,2},{1},{3}}`** (distinct paths=3)

### 등가 비교

| 항목 | 값 |
|------|-----|
| vanilla partition | `{{0,2},{1},{3}}` |
| OTel-scope partition | `{{0,2},{1},{3}}` |
| **partition 일치** | **✅ MATCH** |
| 절대 키 교집합 | 0 (예상된 불일치 — §5.1 문서화 한계) |

**V3(a) rev.4 판정: PASS** — partition 등가 달성.

---

## 파일 변경

| 파일 | 변경 내용 |
|------|---------|
| `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/PjacocoOtelScopeClient.java` | 신규 — OTel-scope/traceId 경로 reusable helper |
| `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V3ArmEquivalencePoc.java` | 신규 test `perRequestOtelScope_yieldsSamePartition` + 관련 helper 메서드 추가 |
| `docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md` §11 | V3(a) rev.4 실측 결과 기록 (PASS) |
| `docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md` | REQ-004 🟡 → 🟢, coverage 1/9 → 2/9 |
| `.superpowers/sdd/task-4b-report.md` | 본 리포트 |

---

## 자기 검토

1. **partition 불일치 시 fail()**: 조건은 `!vanillaPartition.equals(otelPartition)` — 완전 동등성 비교. 어떠한 완화도 없음.
2. **절대 키 교집합=0이어도 PASS**: §5.1 설계 결정에 따른 의도적 허용. PASS 조건을 약화한 것이 아니라 production 요구 속성에 기준을 맞춘 것.
3. **OTel-scope 경로 선택 근거**: `incompleteAttribution=false`, droppedProbes=0 — baggage 경로의 구조적 한계 해소. investigation commit `71e4657` 재현.
4. **PjacocoOtelScopeClient 재사용성**: V2/V3b/V4가 `traceIdFor(index)`, `traceparentFor(traceId)`, `flush(traceId)`, `awaitAndLoad(traceId)` 네 메서드로 동일 경로를 재사용 가능. Math.random 없이 결정론적 traceId 생성(재현성 보장).
5. **우려사항 없음**: OTel 추가 귀속(JPA·async)이 arm을 잘못 merge/split하지 않음을 partition으로 확인. V2·V4에서 교차오염·분산귀속 추가 확인 예정.

---

## 리뷰 수정 (commit f36b5f5)

| Finding | 조치 |
|---------|------|
| F1 non-triviality guard | `perRequestOtelScope_yieldsSamePartition`에서 partition equality 전에 `assertThat(vanillaPartition).anyMatch(g -> g.size() > 1)` 추가. arm MERGING 실증. |
| F2 awaitExecFile threshold | `PjacocoOtelScopeClient.awaitExecFile`: `> 32` → `> 0`. 소형 .exec(V1: 26바이트) 타임아웃 방지. V2/V3b/V4 재사용 안전. |
| F3 dead constants | `TRACE_ID_BASE` (PjacocoOtelScopeClient L43), `VANILLA_TCP_PORT` (V3ArmEquivalencePoc L61) 제거. grep-confirmed 미사용. |

재실행 결과: `perRequestOtelScope_yieldsSamePartition` PASS — partition `{{0,2},{1},{3}}` match, 비singleton {0,2} guard 통과. 기존 `perRequestTestId` 실패는 §11 기록된 baggage 경로 구조적 불일치로 변경과 무관.

---

## JUnit 출력 요약

```
V3ArmEquivalencePoc > REQ-004: per-request arm partition 등가 (rev.4 …) STANDARD_OUT
  [V3part] vanilla  partition={{0,2},{1},{3}}
  [V3part] otel     partition={{0,2},{1},{3}}
  [V3part] V3(a) rev.4 PASS — PARTITION MATCH, distinct-paths=3 partition=[[0, 2], [1], [3]]
  V3a-rev4 PARTITION MATCH partition=[[0, 2], [1], [3]]

BUILD SUCCESSFUL in 54s  failures=0  errors=0
```
