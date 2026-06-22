# 성공 오라클 + 에러 엔벨로프 대응 설계

- 작성일: 2026-06-22
- 대상: `graph-rag-builder`, `test-generator`
- 상태: 설계(brainstorming 산출), 3-벤더 리뷰 반영(rev.2)

## 1. 배경과 문제

특정 SUT는 비즈니스 예외를 전역 핸들러(`BizException` 처리)에서 잡아 **HTTP 200 + 에러 엔벨로프 본문**으로
응답한다. 엔벨로프 형태:

```json
{ "errorServer": "...", "errorCode": "400", "errorMsg": "...", "errorDetail": "...BizException..." }
```

- `errorCode`는 4xx 숫자를 가지되 **JSON 문자열**이다(`"400"`).
- `errorDetail`에는 "BizException" 단어가 포함된다.

이 구조에서 현재 도구가 만드는 테스트는 세 가지 결함을 보인다.

1. **빈약한 단언** — 거의 전부 `notNullValue()` + status/header 존재 여부. 실제 응답 데이터 값 단언이 없다.
2. **branch ~7%** — 분기를 거의 못 탄다. happy/probe 1~2개 path만 존재.
3. **에러 응답을 정답처럼 고정** — 200-wrapped-error를 '기대값'으로 굳힌다.

### 근본원인 (코드 확정)

빌더의 성공 판정이 **HTTP status code만** 본다(`status / 100 == 2`). 응답 본문을 검사하지 않는다.
따라서 200-엔벨로프는 happy success로 **오인**된다.

- **RC-A (성공 오라클)**: 200-엔벨로프를 happy로 오인 → 증상 3, 그리고 generator가 엔벨로프 필드에
  약한 단언 생성(`FixtureComposer.java:206-246`, 성공/에러 분기 없음) → 증상 1.
- **RC-B (입력 합성)**: `ReadInputSynthesizer`가 GET에 유효 도메인 입력을 못 만들어 에러 arm만 탐색 → 증상 2.

**RC-A가 전제다.** 성공 오라클이 틀리면 빌더는 자기가 실패 중인 걸 모른다 → 커버리지 지표 무의미,
잘못된 happy/seed 선택, generator가 에러 고정. RC-A 교정 시 증상 1·3이 풀리고 증상 2가 측정·개선 가능해진다.

## 2. 목표 / 비목표

**목표**
- 빌더가 200-엔벨로프를 FAILURE로 정확히 분류(RC-A).
- 와이어 status(200)는 보존하되, `errorCode`에서 의미상 status를 복원.
- generator가 성공 vs 에러를 분기해 에러 계약을 의도적으로 단언(약한 notNull-only 금지).
- 이 엔벨로프 SUT의 GET-by-id에서 genuine SUCCESS path ≥ 1 도달(RC-B 경계 증분).

**비목표**
- 입력 합성 전반의 해결(기존 단계적 input-discovery 로드맵 잔류).
- 와이어 status 위조(실제 계약과 어긋남).
- 비-엔벨로프 SUT의 기존 동작 변경(기본값은 순수 status-only).

## 3. 아키텍처

### 3.1 `ResponseClassifier` (교체가능, InputOracle 패턴 차용)

- **위치**: `io.graphrag.builder.oracle.ResponseClassifier`(InputOracle와 동일 패키지).
- **시그니처**:
  ```
  입력:  wireStatus: int, body: JsonNode
  출력:  Outcome { kind: SUCCESS | FAILURE, semanticStatus: int, semanticStatusText: String, signal: String }
  ```
  - `semanticStatusText`: 엔벨로프 원본 타입 보존(예: `"400"`). generator 단언이 사용(§5, I-errorCode).
  - `semanticStatus`: 파싱된 int(빌더 내부 분류·리포트용). 파싱 실패 시 `wireStatus`.

- **기본 구현 `StatusOnlyClassifier`**: `wireStatus/100 == 2 → SUCCESS`(기존 동작 동일). **설정 미지정 시 이것** —
  비-엔벨로프 SUT 무영향(후방호환 핵심).
- **엔벨로프 구현 `ErrorEnvelopeClassifier`**(설정형):
  - **판정 술어**: 지정한 트리거 필드가 본문에 **존재 AND non-null AND non-empty**이면 FAILURE.
    (순수 presence-only 금지 — 성공 응답이 `errorCode: null`을 항상 포함하는 SUT 오탐 방지. Claude2-I8/Cursor-I10.)
  - **다중 필드 = OR**(하나라도 충족 시 FAILURE). 기본값 확정.
  - `semanticStatus*` 복원: 지정한 status 필드(기본 `errorCode`)의 값. int 파싱 + 원본 텍스트 동시 보관.

- **설정 표면(CLI, `BuilderCli` 파싱 → `BuildConfig`에 `ClassifierConfig` 필드로 주입)**:
  - `--error-when-present <field>[,<field>...]` (트리거 필드, OR)
  - `--semantic-status-field <field>` (기본 `errorCode`)
  - `--error-detail-field <field>` + `--error-detail-contains <substr>` (선택; §5의 containsString 단언용,
    하드코딩 제거. Claude1-I3/Cursor-I10)
  - 미지정 → `StatusOnlyClassifier`.
  - 주입 경로: `BuildConfig.classifierConfig` → `EndpointExplorationRunner` 생성 시 `ResponseClassifier` 인스턴스화.
    attach 모드·incremental build 모두 동일 기본값(미지정 시 status-only).

### 3.2 파이프라인 전환 (status → outcome) — 전체 변경 지점

`status/100==2`(또는 동등) 판정 지점을 **모두** `Outcome.kind` 기준으로 전환한다. 리뷰로 확정된 전체 목록:

| # | 위치 | 현재 | 전환 |
|---|---|---|---|
| 1 | `CoverageGuidedFuzzer.java:38` | 2xx-first 정렬 | outcome=SUCCESS-first |
| 2 | `CoverageGuidedFuzzer.java:59-60` | `addSeed(body, outcome.status())` raw status | semanticStatus/outcome 전달 |
| 3 | `EndpointExplorationRunner.java:889` | Kafka happy 판정 | outcome=SUCCESS |
| 4 | `EndpointExplorationRunner.java:1168-1171 attachSeeds` | 첫 2xx path에 시드 연결 | 첫 SUCCESS path |
| 5 | `EndpointExplorationRunner.java:1787 verifyAndFilterNonTwoxx` | 2xx 무검증 KEEP | §3.4 정책 |
| 6 | `ExplorationOrchestrator.java:61,83` | dedup 키·path-id가 raw status | §3.3 |
| 7 | `FixtureComposer.java:299 lookupSucceeded` | `expectedStatus/100==2` | outcome=SUCCESS (enveloped-200을 lookup 성공으로 오판→잘못된 seed INSERT 방지. Claude2-I2) |
| 8 | `Generator.java:686 postCreateCleanup` | `expectedStatus 200-299` 게이트 | outcome=SUCCESS (enveloped-200 POST가 깨진 cleanup 유발→DB 정합성 위험 방지. Claude1-I2/Claude2-I1) |

와이어 status는 **위조하지 않는다**. `ExploredPath`에 `outcome`/`semanticStatus`/`semanticStatusText`를
**추가 필드**로 싣고 `expectedStatus`(=와이어 200)는 그대로 둔다.

**record compat 생성자 전략**(Claude1-I7): `ExploredPath`에 신규 canonical 생성자(필드 3개 추가) +
기존 인자수 compat 생성자(신규 필드 `outcome=null`/`wireStatus` 기본) 추가. `EndpointExplorationRunner`의
`ExploredPath` 생성 지점(약 7곳)을 신규 생성자로 갱신. `GraphAsset`·`BuildConfig`도 동일 compat 패턴.
역직렬화 후방호환: 신규 필드 누락 시 compact 생성자에서 기본값(`outcome=SUCCESS@wireStatus`) 흡수.

### 3.3 dedup 키 / path-id

`ExplorationOrchestrator`의 dedup 키는 `status + coverageKey`라, coverage 지문이 다른 genuine-200과
enveloped-200은 **합쳐지지 않는다**(안전). 다만 path-id가 `-s200-`로만 찍혀 FAILURE가 성공처럼 보이는
**관측성 문제**가 있으므로, FAILURE outcome인 path는 path-id에 semanticStatus를 반영(`-s200e400-` 등)하고
dedup 키에 `outcome.kind`를 추가해 동일 coverage라도 성공/실패를 분리한다.

### 3.4 재분류된 200-엔벨로프의 신분과 필터 정책

happy가 아니라 **에러 계약 path**. `discoveredBy = "error-envelope"` 마커 부여(generator 라우팅·진단용,
Claude1-I10). `verifyAndFilterNonTwoxx`(지점 5)는 outcome=FAILURE & 와이어-2xx인 path를 negative-* 와
동일하게 **마커 기반 KEEP**(재현 검증 면제)한다(Cursor-I6). 빌더는 happy 탐색을 계속하고, 끝내 못 찾으면
exploration report에 기록(§3.5).

### 3.5 ExplorationReport 모델 변경

`ExplorationReport.EndpointExploration`에 `String noHappyPathReason`(nullable, 기본 null) 추가
(Claude1-I5). 모든 응답이 엔벨로프-실패면 `"all responses error-enveloped"` 기록. shared-model 변경이므로
§7에 포함.

## 4. RC-B 경계 증분 (코드 정합 서술)

RC-A 적용 전 실제 문제는 "루프 조기 종료"가 아니라 **잘못된 happy/seed 선택**이다(Cursor-I9/Claude2-I6):
- 잘못된 2xx 시드 우선순위(`CoverageGuidedFuzzer:38`) → enveloped-200이 SUCCESS 시드로 선점.
- 첫 2xx path에 seed 부착(`attachSeeds:1168-1171`) → 에러 path에 fixture 오부착.
- pass-2 재탐색(`EndpointExplorationRunner:260-266`, heuristic.table()==null → `SqlSeedResolver`)이
  enveloped-200으로 인해 무력화.

RC-B 증분(측정가능 경계, "전부 해결" 아님):
- §3.2 전환으로 시드 우선순위·seed 부착·pass-2 트리거가 outcome 기준이 되어 **유효입력 재탐색이 유지**된다.
- by-id GET에서 센티널 `"0"`(`pathSentinel`, `EndpointExplorationRunner:1581-1586`) 폴백 경로를 보강:
  outcome=FAILURE & GET-by-id면 pass-2 SQL hint 재시드를 **재시도 예산 내** 강제.
- **재시도 예산 상한**: 엔드포인트당 추가 요청 **N=4회**(상수). 예산 소진 시 best-effort로 FAILURE 기록 후 진행.
- 측정 목표: 엔벨로프 SUT의 GET-by-id에서 genuine SUCCESS path ≥ 1 도달. 더 깊은 합성은 Stage 로드맵 잔류.

## 5. generator 단언 분기

`ExploredPath.outcome`으로 분기(구 그래프 호환: outcome 누락 시 `expectedStatus/100==2`로 폴백, null-guard).

- **SUCCESS path**: 기존 결정성 로직 유지(입력유래·SQL리터럴 → `equalTo`, 서버생성 → `notNullValue`).
- **에러 계약 path**(outcome=FAILURE):
  - `.statusCode(200)`(와이어) +
  - `.body("<semanticStatusField>", equalTo("<semanticStatusText>"))` — **문자열 매칭**(`errorCode`가 JSON
    문자열 `"400"`이므로 `equalTo(400)`(int)는 RestAssured에서 항상 실패. string 보존. Claude1-I11/Claude2-I4/Cursor-I7) +
  - 설정된 경우 `.body("<errorDetailField>", org.hamcrest.Matchers.containsString("<substr>"))`.
  - `errorMsg`/`errorDetail` 전문을 `equalTo`로 고정하지 않는다. 에러 path를 `notNullValue`-only로 두지 않는다.
- **신규 matcher `containsString`**: header 단언이 이미 FQN(`org.hamcrest.Matchers.notNullValue()`,
  `Generator.java:185`)을 쓰므로, **FQN으로 생성**해 Mustache template import 변경 불필요(Claude2-I3).
- **연계 변경**: `lookupSucceeded`(§3.2 #7)·`postCreateCleanup`(#8)을 outcome=SUCCESS 게이트로 전환.

## 6. E2E / 수용기준

### 6.1 샘플 SUT (최소 형태)

`samples/`에 신규 모듈 `samples/error-envelope-service`(또는 order-service 확장 — 구현 시 1택). 최소 구성
(Claude2-I7/Cursor-I11): 단일 `GET /items/{id}` 컨트롤러 + `@RestControllerAdvice` 전역 핸들러가
`BizException`(없는 id)을 200+`{errorServer,errorCode:"404",errorMsg,errorDetail:"...BizException..."}`로 변환,
DB 테이블 `items`(시드 가능한 1행 이상). 정상 id면 비-엔벨로프 엔티티 반환.

### 6.2 수용기준

- **AC1**: 빌더가 enveloped-200을 FAILURE로 분류하고 `errorCode`("404")에서 semanticStatus/Text 복원.
- **AC2**: generator가 에러 path에 `.body("errorCode", equalTo("404"))`(문자열) + (설정 시)
  `errorDetail` `containsString("BizException")` 생성. 약한 notNullValue-only 금지.
- **AC3a**(루프): outcome=FAILURE인 GET-by-id에서 탐색이 종료되지 않고 pass-2 재시드를 예산 N=4 내 재시도.
- **AC3b**(도달): `items`에 유효 id 시드 시, 예산 내 genuine SUCCESS(비-엔벨로프) path ≥ 1 도달 +
  해당 엔드포인트 branch coverage가 RC-A 미적용 대비 상승(샘플 SUT에서 측정).
- **AC4**: 회귀 — 기본 classifier=status-only라 비-엔벨로프 SUT 무영향. 검증 = `e2e/run-e2e.sh`(order-service)
  + `e2e/run-gateway-e2e.sh` + `e2e/run-legacy-tram-sleuth-e2e.sh`가 GREEN 유지. (외부 7-SUT 스윕은 repo 밖
  수동 실행 — 재현 절차: 각 SUT를 builder attach로 돌려 graph.json 생성 후 generator·컴파일. CI 자동화는 범위 밖.)

### 6.3 에러처리/엣지

- `errorCode` 4xx 숫자 파싱 불가 → `semanticStatus=wireStatus`, `semanticStatusText`는 원본 유지.
- 트리거 필드 부분 존재 → OR·non-null 술어대로 판정.
- 설정 미지정 → 순수 status-only(기존 동작과 동일).
- 성공 응답에 `errorCode: null`/`""` → non-null AND non-empty 술어로 SUCCESS 유지.

## 7. 영향 범위 / 후방호환

- **신규**: `ResponseClassifier` + `StatusOnlyClassifier`/`ErrorEnvelopeClassifier`, `Outcome` 레코드,
  `ClassifierConfig`(BuildConfig 필드), CLI 플래그(`BuilderCli`), 샘플 SUT(`samples/error-envelope-service`).
- **모델 변경**: `ExploredPath`(+outcome/semanticStatus/semanticStatusText), `ExplorationReport`(+noHappyPathReason).
  둘 다 compat 생성자.
- **변경 지점(§3.2 표 8곳)**: CoverageGuidedFuzzer(38,59-60), EndpointExplorationRunner(889,1168-1171,1787,
  생성 지점 7곳, 1581-1586), ExplorationOrchestrator(61,83), FixtureComposer(299, 227-242 단언분기),
  Generator(686 postCreateCleanup, 단언 라우팅).
- **template**: 변경 없음(containsString FQN 생성).
- **후방호환**: 신규 필드 기본값 흡수, 기본 classifier=status-only → 기존 e2e 회귀 무영향.

## 8. 미해결 / 리뷰 포인트

- RC-B 재시도 예산 N=4의 적정값(샘플 SUT 측정 후 튜닝 가능).
- `error-envelope-service` 신규 모듈 vs order-service 확장 — 구현 착수 시 1택(신규 모듈 권장: 회귀 격리).
