# 성공 오라클 + 에러 엔벨로프 대응 설계

- 작성일: 2026-06-22
- 대상: `graph-rag-builder`, `test-generator`
- 상태: 설계(brainstorming 산출), 3-벤더 리뷰 대기

## 1. 배경과 문제

특정 SUT는 비즈니스 예외를 전역 핸들러(`BizException` 처리)에서 잡아 **HTTP 200 + 에러 엔벨로프 본문**으로
응답한다. 엔벨로프 형태:

```json
{ "errorServer": "...", "errorCode": "400", "errorMsg": "...", "errorDetail": "...BizException..." }
```

- `errorCode`는 4xx 숫자(문자열) 값을 가진다.
- `errorDetail`에는 "BizException" 단어가 포함된다.

이 구조에서 현재 도구가 만드는 테스트는 세 가지 결함을 보인다.

1. **빈약한 단언** — 거의 전부 `notNullValue()` + status/header 존재 여부. 실제 응답 데이터 값 단언이 없다.
2. **branch ~7%** — 분기를 거의 못 탄다. happy/probe 1~2개 path만 존재.
3. **에러 응답을 정답처럼 고정** — 200-wrapped-error를 '기대값'으로 굳힌다.

### 근본원인 (코드 확정)

빌더의 성공 판정이 **HTTP status code만** 본다(`status / 100 == 2`). 응답 본문을 검사하지 않는다.

- `graph-rag-builder/.../explore/CoverageGuidedFuzzer.java:38` — 2xx 우선순위
- `graph-rag-builder/.../run/EndpointExplorationRunner.java:889` — Kafka happy 판정
- `graph-rag-builder/.../run/EndpointExplorationRunner.java:1171` — 시드를 첫 2xx path에만 연결
- `graph-rag-builder/.../run/EndpointExplorationRunner.java:1787 verifyAndFilterNonTwoxx` — 2xx는 검증 없이 무조건 KEEP

따라서 200-엔벨로프는 happy success로 **오인**된다. 그 결과:

- **RC-A (성공 오라클)**: 200-엔벨로프를 happy로 오인 → 증상 3, 그리고 generator가 엔벨로프 필드에
  약한 단언 생성(`FixtureComposer.java:206-246`, 성공/에러 분기 없음) → 증상 1.
- **RC-B (입력 합성)**: `ReadInputSynthesizer`가 GET에 유효 도메인 입력을 못 만들어(휴리스틱 테이블 해석
  실패 → pass-2는 SQL 포착 의존인데 에러 조기 종료로 SQL 미포착 → 센티널 `"0"`/`"missing"` 폴백,
  `EndpointExplorationRunner.java:1581-1586`) 에러 arm만 탐색 → 증상 2.

**RC-A가 전제다.** 성공 오라클이 틀리면 빌더는 자기가 실패 중인 걸 모른다 → 커버리지 지표 무의미,
탐색 조기 종료, generator가 에러 고정. RC-A 교정 시 증상 1·3이 풀리고 증상 2가 측정·개선 가능해진다.

## 2. 목표 / 비목표

**목표**
- 빌더가 200-엔벨로프를 FAILURE로 정확히 분류(RC-A).
- 와이어 status(200)는 보존하되, `errorCode`의 4xx에서 의미상 status를 복원.
- generator가 성공 vs 에러를 분기해 에러 계약을 의도적으로 단언(약한 notNull-only 금지).
- 이 엔벨로프 SUT의 GET-by-id에서 genuine SUCCESS path ≥ 1 도달(RC-B 경계 증분).

**비목표**
- 입력 합성 전반의 해결(기존 단계적 input-discovery 로드맵 잔류).
- 와이어 status 위조(실제 계약과 어긋남).
- 비-엔벨로프 SUT의 기존 동작 변경(기본값은 순수 status-only).

## 3. 아키텍처

### 3.1 `ResponseClassifier` (교체가능, InputOracle 패턴 차용)

```
입력:  wireStatus: int, body: JsonNode
출력:  Outcome { kind: SUCCESS | FAILURE, semanticStatus: int, signal: String }
```

- **기본 구현(`StatusOnlyClassifier`)**: `wireStatus/100 == 2 → SUCCESS`(기존 동작 동일). 설정 미지정 시 이것.
- **엔벨로프 구현(`ErrorEnvelopeClassifier`)**: presence 기반 설정형.
  - 설정: 실패 신호 필드 목록(예: `errorCode`). 해당 필드가 본문에 **존재**하면 FAILURE.
  - `semanticStatus` 복원: `errorCode`를 정수 파싱(4xx). 파싱 실패 시 `wireStatus` 유지.
  - `signal`: 어느 술어가 걸렸는지(진단/리포트용).

설정 표면(CLI): `--error-when-present <field>[,<field>...]`, `--semantic-status-field <field>`(기본
`errorCode`). 미지정 시 `StatusOnlyClassifier`.

### 3.2 파이프라인 전환 (status → outcome)

`status/100==2`가 박힌 판정 지점을 `Outcome.kind == SUCCESS` 기준으로 교체한다(상기 4개 지점).

- 와이어 status는 **위조하지 않는다**. `ExploredPath`에 `outcome`/`semanticStatus`를 **추가 필드**로
  싣고 `expectedStatus`(=와이어 200)는 그대로 둔다.
- 후방호환: `GraphAsset`/`ExploredPath`의 신규 필드는 기존 compact 생성자 패턴(누락 시 기본값)으로 흡수.

### 3.3 재분류된 200-엔벨로프의 신분

happy가 아니라 **에러 계약 path**(negative-validation과 유사 신분). 빌더는 happy 탐색을 계속하고,
끝내 못 찾으면 exploration report에 "no happy path (all responses error-enveloped)"를 기록한다.

## 4. RC-B 경계 증분

RC-A의 FAILURE를 **피드백 신호**로 사용한다(측정가능 경계, "전부 해결" 아님).

- enveloped-200을 성공으로 받지 않음 → GET에서 outcome=FAILURE면 탐색 루프가 멈추지 않고 유효입력 재시도.
- 시드된 유효 id가 by-id GET 입력에 실제로 도달하도록 보강(휴리스틱 해석 실패 시 센티널 `"0"`로 떨어지는
  경로, pass-2 SQL 미포착 무력화 구간).
- 측정 목표: 엔벨로프 SUT의 GET-by-id에서 genuine SUCCESS path ≥ 1 도달. 더 깊은 합성은 Stage 로드맵 잔류.

## 5. generator 단언 분기

`ExploredPath.outcome`으로 분기한다.

- **SUCCESS path**: 기존 결정성 로직 유지(입력유래·SQL리터럴 → `equalTo`, 서버생성 → `notNullValue`).
- **에러 계약 path**:
  - `.statusCode(200)`(와이어) +
  - `.body("errorCode", equalTo(<semanticStatus>))`(결정적) +
  - `.body("errorDetail", containsString("BizException"))`.
  - `errorMsg`/`errorDetail` 전문을 `equalTo`로 고정하지 않는다(취약). 에러 path를 `notNullValue`-only로
    두지 않는다.
- 신규 matcher 지원: `containsString`(현재 `equalTo`/`notNullValue`만, `FixtureComposer.java:227-242`).

## 6. E2E / 수용기준

**E2E(outer loop)**: `BizException`을 전역 핸들러가 200+엔벨로프로 감싸는 샘플 SUT를 `samples/`에 추가.

- **AC1**: 빌더가 enveloped-200을 FAILURE로 분류하고 `errorCode`(4xx)에서 semanticStatus 복원.
- **AC2**: generator가 에러 path에 `errorCode` 값 단언 + `errorDetail` `containsString` 생성
  (약한 notNullValue-only 금지).
- **AC3**: 해당 SUT GET-by-id에서 genuine SUCCESS path ≥ 1 도달(branch coverage 측정 상승).
- **AC4**: 회귀 — 기존 7-SUT 스윕 GREEN 유지. 기본 classifier=status-only라 비-엔벨로프 SUT 무영향.

**에러처리/엣지**
- `errorCode` 4xx 숫자 파싱 불가 → 와이어 status 유지.
- 엔벨로프 필드 부분 존재 → presence 술어대로 판정.
- 설정 미지정 → 순수 status-only(기존 동작과 동일).

## 7. 영향 범위 / 후방호환

- 신규: `ResponseClassifier` 인터페이스 + 2개 구현, `ExploredPath.outcome/semanticStatus` 필드,
  CLI 플래그, `FixtureComposer` 분기 + `containsString` matcher, 샘플 SUT.
- 변경: `status/100==2` 판정 4개 지점 → outcome 기준.
- 후방호환: 신규 필드 기본값 흡수, 기본 classifier=status-only → 기존 7-SUT 회귀 무영향.

## 8. 미해결 / 리뷰 포인트

- `--error-when-present`의 다중 필드 AND/OR 의미(기본 OR=하나라도 존재 시 FAILURE 제안).
- `semanticStatus`를 generator가 `equalTo`로 단언할 때 문자열("400") vs 정수(400) 타입(엔벨로프 원본 타입 보존).
- RC-B 증분의 정확한 종료 조건(재시도 예산 상한).
