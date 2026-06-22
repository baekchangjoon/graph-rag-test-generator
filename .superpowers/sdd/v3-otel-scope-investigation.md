# V3(b) 조사: OTel-scope traceId 경로 — JwtAuthenticationFilter probe 캡처 가설 검증

- 조사일: 2026-06-23
- 담당: Claude Code (investigation agent)
- 배경: Task 4 REQ-004 FAIL (pjacoco baggage 경로 JwtAuthenticationFilter 4개 probe drop)
- 가설: OTel servlet 계측이 filter chain 전체를 감싸므로 traceId scope 경로는 JwtFilter probe를 캡처할 수 있다

---

## 방법론

### 에이전트 JVM 옵션

**VANILLA 벡터:**
```
JAVA_TOOL_OPTIONS=-javaagent:<tmpdir>/vanilla/jacocoagent.jar=output=tcpserver,address=127.0.0.1,port=<random>
```
- `CoverageClient.dump(reset=true)` per request → `ExecutionDataStore` delta → `CoverageFingerprint.of`

**OTel-scope 벡터:**
```
JAVA_TOOL_OPTIONS=
  -javaagent:/Users/changjoonbaek/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar
  -javaagent:<pjacoco-agent.jar>=destfile=<dir>,port=6310,includes=org.springframework.samples.petclinic.*,traceKeyAutoCreate=true
```
- 각 요청에 `traceparent: 00-<32hex traceId>-0000000000000001-01` 헤더 전송
- 요청 직후 `POST http://127.0.0.1:6310/__coverage__/test/stop?testId=<traceId>&result=passed` flush
- `.exec` 파일 대기 후 `ExecFileLoader.load` → `CoverageFingerprint.of`

**OTel 환경 변수:**
```
OTEL_METRICS_EXPORTER=none
OTEL_TRACES_EXPORTER=none
OTEL_LOGS_EXPORTER=none
OTEL_SERVICE_NAME=petclinic-v3otel-probe
```

pjacoco agent version: 1.3.0 (from `pjacoco-agent.jar`)  
OTel agent version: 2.11.0 (from petclinic startup log)  
petclinic: spring-petclinic-4.0.0-SNAPSHOT.jar, Java 17  
appClasses: 52 classes (`org/springframework/samples/petclinic/**`)

### 입력 시퀀스

| req | lastName= | arm |
|-----|-----------|-----|
| 0 | `` (empty) | results-found (전체 목록) |
| 1 | `ZZZNONE` | not-found |
| 2 | `Davis` | results-found |
| 3 | `Franklin` | results-found |

---

## 실행 결과

### Vanilla 벡터

```
vanilla req-0 [lastName=] → key=35763958eb8eef2d  jwtFilter=YES
vanilla req-1 [lastName=ZZZNONE] → key=e1859cc39e870bce  jwtFilter=YES
vanilla req-2 [lastName=Davis] → key=35763958eb8eef2d  jwtFilter=YES
vanilla req-3 [lastName=Franklin] → key=3a35ec74ec7027b  jwtFilter=YES
vanilla keys (3): [35763958eb8eef2d, e1859cc39e870bce, 3a35ec74ec7027b]
vanilla JwtAuthenticationFilter captured: true
```

→ vanilla은 모든 4 요청에서 `JwtAuthenticationFilter` probe를 캡처함 (기준선 유효)  
→ arm 분리: 3 distinct keys (req-0=req-2, req-1 별도, req-3 별도) — Task-4와 동일

### OTel-scope 벡터

```
otel req-0 [lastName=] traceId=0000...abcdef… → key=b13e082e8378dc20  jwtFilter=YES  exec=607 bytes
otel req-1 [lastName=ZZZNONE] traceId=0000...bcdef… → key=7650f252052ff381  jwtFilter=YES  exec=415 bytes
otel req-2 [lastName=Davis] traceId=0000...cdef… → key=b13e082e8378dc20  jwtFilter=YES  exec=607 bytes
otel req-3 [lastName=Franklin] traceId=0000...def… → key=5e4b01494e276852  jwtFilter=YES  exec=607 bytes
otelScope keys (3): [b13e082e8378dc20, 7650f252052ff381, 5e4b01494e276852]
otelScope JwtAuthenticationFilter captured: true
```

### OTel-scope JSON 메타데이터 (`.json` 사이드카)

| testId | classCount | status | incompleteAttribution | droppedProbes |
|--------|------------|--------|-----------------------|---------------|
| `0000...abcdef0123456789` | 8 | complete | **없음** | **없음** |
| `0000...bbcdef012345678a` | 5 | complete | **없음** | **없음** |
| `0000...cbcdef012345678b` | 8 | complete | **없음** | **없음** |
| `0000...dbcdef012345678c` | 8 | complete | **없음** | **없음** |

→ `incompleteAttribution: false` (JSON에 키 자체 없음 = 0 drop)  
→ Task-4 baggage 경로의 `"droppedProbes":4,"incompleteAttribution":true`와 **대조**

### OTel-scope exec 내 포함 클래스 (req-0 hex 분석)

```
org/springframework/samples/petclinic/model/BaseEntity
org/springframework/samples/petclinic/security/JwtAuthenticationFilter   ← Task-4 baggage에선 drop
org/springframework/samples/petclinic/owner/OwnerController
org/springframework/samples/petclinic/owner/PetType
org/springframework/samples/petclinic/owner/Owner
org/springframework/samples/petclinic/owner/Pet
org/springframework/samples/petclinic/model/Person
org/springframework/samples/petclinic/model/NamedEntity
```
(총 8 classes)

Task-4 baggage 경로 req-0: `BaseEntity`, `NamedEntity`, `OwnerController` (3 classes, JwtFilter 없음)

### 등가 비교

```
vanilla  set size=3 keys=[35763958eb8eef2d, e1859cc39e870bce, 3a35ec74ec7027b]
otelScope set size=3 keys=[b13e082e8378dc20, 7650f252052ff381, 5e4b01494e276852]
intersection size=0
only-in-vanilla (3): [35763958eb8eef2d, e1859cc39e870bce, 3a35ec74ec7027b]
only-in-otelScope (3): [b13e082e8378dc20, 7650f252052ff381, 5e4b01494e276852]
```

**VERDICT: PARTIAL — OTel scope는 JwtFilter probe를 캡처하지만 vanilla와의 key 집합은 여전히 불일치 (intersection=0)**

pjacoco startup summary (petclinic log):
```
completed=4 partial=81 droppedNoContext=224 unattributedDrops=40 ambiguousDrops=184
```

---

## 분석

### 가설 검증 결과

**부분 확인 + 부분 반증:**

1. **OTel scope가 JwtAuthenticationFilter probe를 캡처**: **CONFIRMED**  
   - OTel javaagent의 servlet 계측은 HTTP 필터 체인 전체를 감싼다. `ThreadLocalContextStorage#attach`는 OTel filter가 진입할 때 호출되므로, Spring Security `JwtAuthenticationFilter`(pre-servlet)가 실행되기 전에 pjacoco 커버리지 컨텍스트가 활성화된다.
   - OTel-scope exec에 `JwtAuthenticationFilter`가 포함됨으로써 이 메커니즘이 실측 확인됨.
   - `incompleteAttribution=false`, `droppedProbes=0` — probe 손실 없음.

2. **OTel-scope key 집합이 vanilla key 집합과 일치**: **REFUTED (intersection=0)**  
   - arm 분리 패턴 자체는 동일 (OTel-scope도 3 distinct keys, req-0=req-2, req-1 별도, req-3 별도).  
   - 그러나 FNV-1a 해시값이 모두 다름 → 등가 게이트(REQ-004) 통과 불가.

### 키 불일치 근본 원인

**OTel-scope vs vanilla의 probe 집합 차이:**

| 항목 | vanilla (reset dump) | OTel-scope (traceId flush) | baggage (task-4) |
|------|---------------------|---------------------------|-----------------|
| 포함 클래스 수 | (probe 패턴 다름) | 8 | ~3 |
| JwtAuthenticationFilter | YES | YES | **NO** |
| 추가 도메인 클래스 | (전체 실행 probe 포함) | Owner, Pet, PetType, Person | 없음 |
| incompleteAttribution | N/A | false | **true** |

OTel scope는 **JPA entity 구체화 구간도 포함**한다. OTel instrumentation이 DB/JPA 쿼리에 대한 child span을 생성하면서 entity class(Owner, Pet, PetType, Person)의 probe도 traceId store에 귀속된다. 결과적으로 OTel-scope exec는 vanilla의 reset-based delta와 다른 probe 집합을 가진다:
- vanilla reset: JPA proxy가 같은 스레드에서 실행되므로 probe가 delta에 포함되지만, probe의 정확한 비트 패턴이 다를 수 있다 (reset 타이밍, 누적 probe 여부).
- OTel-scope: OTel DB span의 내부/외부 경계에서 scope enter/exit가 발생하므로 probe 귀속이 세분화됨.

**핵심:** OTel scope 경로는 JwtFilter probe 문제를 해결하지만, vanilla와 동일한 per-request delta를 줄 수 없다 — probe 귀속의 의미론이 근본적으로 다르다.

---

## 결론

| 질문 | 답 |
|------|-----|
| OTel-scope traceId 경로가 JwtFilter probe를 캡처하는가? | **YES** (OTel span이 filter chain 전체를 감쌈) |
| incompleteAttribution 플래그? | **false** (droppedProbes=0) |
| OTel-scope key 집합이 vanilla와 일치하는가? | **NO** (intersection=0, 동일 arm 분리 패턴이지만 probe 내용 다름) |
| REQ-004(V3(a) 등가 게이트) 통과 가능한가? | **불가** — OTel-scope 경로를 써도 vanilla와 등가 불가 |

### 이 결과의 의미

- **baggage 경로 → OTel-scope 경로로 전환해도 REQ-004를 통과할 수 없음**: 가설이 "JwtFilter capture" 측면에서는 확인됐지만 "등가성" 측면에서는 반증됨.
- **JwtFilter probe drop은 해결됐으나 새로운 불일치가 발생**: OTel scope는 JPA entity class probe를 추가로 귀속시켜 vanilla와 다른 fingerprint를 생성.
- **arm 분리 패턴 자체는 보존**: OTel-scope도 3 distinct arms를 올바르게 구분함. pjacoco의 per-request 분리 능력은 이 경로에서도 유효.
- **pjacoco와 vanilla의 등가 달성을 위한 다음 방향**:
  1. `CoverageFingerprint.appClasses`에서 OTel instrumented classes를 제외 (의미 약화)
  2. vanilla도 OTel-scope와 동일한 방식으로 probe를 수집 (vanilla 정의 변경)
  3. V3(a) 게이트 자체를 재정의 — "probe-level identical" 대신 "arm-separating power equivalent"로 완화
  4. pjacoco baggage 경로에서 JwtFilter drop을 수용하고 appClasses에서 pre-servlet 클래스 제외

---

## 참고: 비교 데이터 요약

| 벡터 | req-0 key | req-1 key | req-3 key | JwtFilter | incompleteAttribution |
|------|-----------|-----------|-----------|-----------|----------------------|
| vanilla | 35763958eb8eef2d | e1859cc39e870bce | 3a35ec74ec7027b | YES | N/A |
| OTel-scope | b13e082e8378dc20 | 7650f252052ff381 | 5e4b01494e276852 | **YES** | **false** |
| baggage (task-4) | 64fa3e5a98eb12d7 | be0bf6035ce60b56 | d42c806d501d3b11 | **NO** | **true** |

세 벡터 모두 3 distinct arms를 생성. 키는 각각 완전히 다름.
