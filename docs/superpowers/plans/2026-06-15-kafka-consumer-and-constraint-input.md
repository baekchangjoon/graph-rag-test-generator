# 구현 계획: Kafka @KafkaListener 지원 + 제약-aware happy 입력 (v2, 3-모델 리뷰 반영)

- 작성일: 2026-06-15
- 브랜치: `worktree-feat-kafka-consumer-and-constraint-input` (main 기준; PR #30과 독립)
- 동기: petclinic/notification 미커버 사유 분석 — (#2) 복합 검증 체인 만족 happy 합성 불가, (Kafka) @KafkaListener consumer HTTP 미도달.
- 관련 메모리: `input-discovery-staged-roadmap`, `coverage-handler-class-scoping`

---

## 0. 두 기능 (독립, 한 브랜치 phased: A → B1 → B2)

| | 문제 | 해결 |
|---|---|---|
| **A. 제약-aware happy (#2)** | `SampleInputSynthesizer`가 타입 기본값으로 POST body happy 합성 → 첫 검증 가드에서 throw → 이후 가드 미실행 | `ValidationConstraintExtractor`(이미 존재)의 단일필드 제약을 **happy 합성에 주입**해 유효값 선택 |
| **B. Kafka consumer** | @KafkaListener consumer HTTP 미도달 → 분기 0. consumer가 쓰는 데이터 미충족 → read 빈 결과 | WS 서브시스템 **패턴**(인덱서/캡처러너/모델/생성)을 차용해 토픽에 유효 이벤트 발행 → consumer 커버 + SQL 캡처 |

> **리뷰 반영 핵심 정정**: (1) `happyInput`은 fieldConstraints를 **안 받음** → 시그니처 확장 필요. (2) @KafkaListener는 @SendTo reply가 **없어** WS의 request/reply await와 동형 아님 → **fire-and-forget + 명시적 폴링 종료조건**으로 재설계. (3) consumer→read 부수효과는 **순서·probe id 불변식** 또는 best-effort. (4) 토픽 `${prop}`은 ComposeInspector로 해석 불가 → application.yml 파싱/ skip.

---

## 1. Feature A — 제약-aware happy 입력

### 1.1 근본 원인 (코드 검증됨)
- `run/SampleInputSynthesizer`: 생성자로 `enumConstants`만 받음. `synthesize(BodyShape, List<TableSchema>)`가 타입 기본값(int=1, date=2037, email필드=probe@…). **Bean Validation 미고려** → `roomNumber=1`(<100) 등 위반 → handler가 첫 가드에서 throw.
- `index/ValidationConstraintExtractor`: `record FieldConstraint(String field, Kind kind, long numArg, String strArg)`, `Kind = {NOT_NULL, NOT_BLANK, SIZE_MIN, SIZE_MAX, MIN, MAX, POSITIVE, POSITIVE_OR_ZERO, NEGATIVE, NEGATIVE_OR_ZERO, EMAIL, PATTERN}`. 현재 `InputMutator`(변이)만 소비. **happy 경로 미도달**(grep: SampleInputSynthesizer에 FieldConstraint 참조 0).
- 적용 범위: **POST/PUT body**(SampleInputSynthesizer). GET/by-id path 합성(ReadInputSynthesizer)은 A 범위 밖.

### 1.2 설계 — 값 선택 규칙 (Kind 전체 명시)
신규 오버로드 `synthesize(BodyShape, List<TableSchema>, Map<String,List<FieldConstraint>> fieldConstraints)`. 기존 `synthesize(shape, tables)`는 `synthesize(shape, tables, Map.of())` 위임(**기존 동작 불변**). enumConstants는 현행대로 생성자.

필드별로 적용 가능한 모든 제약을 **교집합 범위**로 좁힌 뒤 그 안의 결정적 값을 고른다(범위 비면 MIN/SIZE_MIN 우선):

| Kind | happy 값 | 비고 |
|---|---|---|
| `MIN(m)` | `max(현재기본, m)` | 정수 하한 |
| `MAX(M)` | `min(현재기본, M)` (그리고 ≥ 적용된 MIN) | 정수 상한 |
| `SIZE_MIN(n)` | 길이 ≥ n 문자열('a'×max(n,현재길이)) | |
| `SIZE_MAX(N)` | 길이 ≤ N 으로 자름 | |
| `EMAIL` | `probe@example.com` (현행) | |
| `NOT_NULL`/`NOT_BLANK` | null/빈문자 아님 (현행) | |
| `POSITIVE` | `max(1, …)` (현행 1로 이미 충족) | |
| `POSITIVE_OR_ZERO` | `0` 이상 (현행 1 충족) | |
| `NEGATIVE` | `-1` | 현행 1은 **위반** → 처리 필요 |
| `NEGATIVE_OR_ZERO` | `0` | 현행 1은 위반 → 처리 |
| `PATTERN(rx)` | **skip**(복잡도 대비 효과 불명; 현행값 유지) | 한계 명시 |

- **단일필드만**: inter-field/수학 제약(예: `deposit*1.1≥nights*rate`, `VIP→loyalty≥500`)은 A 범위 밖. happy는 enum을 **첫 상수**로 둔다(가드 분석 없이 첫 상수 선택 = 휴리스틱; 첫 상수가 추가 가드를 켜면 그 분기는 여전히 throw — 한계 인정, 회귀 아님). 반대 arm은 기존 `InputMutator.joint/enumValues` 변이가 담당.
- petclinic Reservation 가드 수치(11가드/21분기)는 **외부 SUT 관측치(repo 미포함, 미검증 가설)** — 실측 후 핀.

### 1.3 배선 (정정)
- `EndpointExplorationRunner.happyInput(...)` 정적 메서드에 `Map<String,List<FieldConstraint>> fieldConstraints` 파라미터 **추가**. 내부 2곳 `new SampleInputSynthesizer(enumConstants).synthesize(shape, tables)`(현 389, 405행) → 신규 오버로드로 교체.
- `run(...)`(현 126행)이 보유한 `fieldConstraints`를 happyInput 호출(현 137행)에 전달.
- `WsCaptureRunner`(현 53행 `new SampleInputSynthesizer(...)`)는 A 범위 밖(WS payload). 무-제약 오버로드 유지.

### 1.4 E2E/수용 기준 (A) — falsifiable
1. **A 단위(repo 내 결정적)**: `SampleInputSynthesizerTest` — `MIN(100)` 필드 happy 값 ≥100; `SIZE_MIN(2)` 길이 ≥2; `NEGATIVE` → 음수; 무-제약 → 기존값 동일.
2. **petclinic(외부 SUT, 실측)**: `post-api-reservations`에 **201 path ≥1개** 존재 + 그 path SQL에 `INSERT … reservation` 캡처(= create 성공 도달). `ReservationService#create` 커버 분기 수 baseline 대비 상승값 측정 후 **상수로 핀**(회귀 가드 등록).
3. **회귀**: 기존 `SampleInputSynthesizerTest`·order-service e2e 무변경 GREEN.

### 1.5 TDD (A)
- A1 red: `SampleInputSynthesizerTest`(§1.4-1 케이스, FieldConstraint 직접 생성). green: 오버로드 + 값 규칙.
- A2: happyInput/run 배선. 검증: order e2e + petclinic 실측(§1.4-2).

---

## 2. Feature B — Kafka @KafkaListener 지원 (WS 패턴 차용, await는 비동형)

### 2.1 컴포넌트 (WS 패턴 미러, 시그니처 동형)
| 단계 | WS (기존, 인용) | Kafka (신규) |
|---|---|---|
| 인덱싱 | `WsEndpointIndexer`(@MessageMapping) → `WsEndpoint` | `KafkaListenerIndexer`(@KafkaListener) → `KafkaConsumer(id, topic, groupId, handlerClass, handlerMethod, payloadType)` |
| 캡처 | `WsCaptureRunner.run(WsEndpoint, BodyShape, List<TableSchema>)` | `KafkaCaptureRunner.run(KafkaConsumer, BodyShape, List<TableSchema>)` (동일 시그니처 형태) |
| 모델 | `WsExchange(id, wsEndpointId, payload, …, capturedSqlIds)` | `KafkaExchange(id, kafkaConsumerId, topic, payload, capturedSqlIds)` |
| Asset | `wsEndpoints/wsExchanges` | `kafkaConsumers/kafkaExchanges` |
| 생성(B2) | `Generator.generateWs`+`ws-test-class.mustache`+`StompHelper` | `generateKafka`+`kafka-test-class.mustache`+`KafkaHelper` |

### 2.2 토픽 해석 (정정)
- MSA 토픽은 **대부분 리터럴**: notification `graph.updated`/`comment.created`, analytics `diary.created`/`mood.logged`/`post.created` — 전부 리터럴(검증됨). → 1차 타깃은 prop 해석 불필요.
- prop 형(`${mindgraph.topics.diary-created}`)은 `--sut-resources`의 `application.yml`/`.properties`를 YAML/Properties 파서로 직접 해석. **해석 실패 시 단일 규칙: 해당 consumer skip + 경고 로그(회귀 아님).** (ComposeInspector는 docker-compose 전용이라 사용 안 함.)

### 2.3 B1 — 빌더-side 캡처 (consumer 커버 + 데이터 채움)
`KafkaCaptureRunner.run(consumer, shape, tables)`:
1. happy payload 합성(`SampleInputSynthesizer`, 제약-aware 재사용). `eventId`/`userId` 등 식별자는 **공통 ProbeId 헬퍼**로 도출(아래 2.4 불변식).
2. `KafkaProducer<String,String>` 생성 — bootstrap servers는 분석환경에서 노출: `AnalysisEnvironment`가 `SPRING_KAFKA_BOOTSTRAP_SERVERS`를 SUT에 주입하므로, 같은 값을 빌더에도 전달(env getter 추가). producer로 `consumer.topic()`에 JSON 발행.
3. **완료 감지 (fire-and-forget + 폴링, WS await와 비동형)**:
   - 발행 직전 `logStart=sut.logOffset()`. 발행 후 **폴링**: 250ms 간격, 최대 8초.
   - **1차 종료조건**: `sut.readLogRange(logStart, now)`에 consumer가 만든 SQL(INSERT/SELECT, consumer가 쓰는 테이블)이 출현. (consumer는 보통 DB/Redis write → SQL 로그 발생.)
   - **2차(SQL-less consumer, 예: Redis-only)**: `coverage.dump(true)` delta에 `consumer.handlerClass`의 분기가 출현.
   - **타임아웃 시**: false-negative와 "정상 no-op(dedup early-return)"을 **구분 불가** → 해당 payload skip + 경고(회귀 아님). 핸들러가 아무 신호도 안 내면 커버 0 유지(현행과 동일).
   - 발행들 사이를 await로 **직렬화**(동시 consumer/HTTP SQL 혼입 방지). KafkaCapture 단계 전체를 HTTP 탐색과 직렬 실행.
4. `logEnd` 확정 → SQL 캡처 → `KafkaExchange`+`CapturedSql`. 커버리지 cumulative 병합.
5. BuilderCli 배선: `kafkaIndex.consumers()` 루프 → `KafkaCaptureRunner.run` → 누적 → GraphAsset. **--with-kafka 없으면 consumers 빈 리스트 → 완전 no-op(기존 동작 불변).**

### 2.4 부수효과(consumer→read) 불변식 — 순서 + id 일치
- **순서 보장**: BuilderCli에서 **Kafka 캡처 루프를 HTTP 엔드포인트 탐색 루프보다 먼저** 실행. read 엔드포인트 탐색 시점엔 consumer가 이미 데이터를 씀.
- **id 일치**: Kafka payload의 `userId`와 read happy의 path/query probe 값을 **동일 ProbeId 헬퍼**에서 도출(공통 함수). 그래야 `GET /…/{userId}`가 consumer가 쓴 행을 조회.
- 단, 이 부수효과는 **bonus**로 취급: read non-empty 분기 향상이 일차 목표가 아니라 consumer 커버가 일차. 순서/일치가 깨져도 회귀 아님(read는 빈 200 유지).
- **dedup 결정성**: consumer가 `eventId`로 dedup(예: notification Redis SET)하면 재실행 시 no-op 가능. 정책: **payload eventId에 실행 단위 prefix**(빌더 run id)를 붙여 매 실행 새 키 → 재실행해도 dedup-no-op 안 됨. (run id는 결정적 입력으로 주입, Math.random 미사용.)

### 2.5 B2 — Kafka 테스트 생성 (조건부, A·B1 후 승인)
- `Generator.generateKafka` + `kafka-test-class.mustache`: `KafkaHelper.send(topic, payload)` + 완료 폴링(B1과 동일 종료조건의 테스트版) + side-effect 단언(read 엔드포인트 200/non-empty 또는 DB row).
- `testlib`에 `KafkaHelper`(KafkaProducer 래퍼) + `TestScope.kafka(bootstrap)`. e2e harness: Testcontainers Kafka(이미 `--with-kafka` 패턴) 또는 e2e compose에 broker 추가.
- 결정성: probe id/eventId run-scoped, fresh DB 재현. 병렬: payload에 testId 임베드(WS marker 패턴).

### 2.6 E2E/수용 기준 (B) — falsifiable
1. **notification(외부 SUT, 실측)**: 빌더가 `kafkaConsumers`에 `onCommentCreated`/`onGraphUpdated` 인덱싱; 발행 후 그 consumer의 `handlerClass` 분기가 covered 집합에 ≥1 진입(baseline 0). (bonus) `findByUserId` SQL이 non-empty 결과 → 200 데이터.
2. **analytics(외부 SUT, 실측)**: `onDiaryCreated`/`onMoodLogged`/`onPostCreated` consumer 분기 진입.
3. **회귀 가드(repo 내, 결정적)**: §4 order-service @KafkaListener → `BuilderE2eTest`가 (a) `kafkaConsumers`에 신규 consumer id 인덱싱, (b) 그 exchange에 consumer의 INSERT `CapturedSql`(table=order_events) 포함 단언. **회귀 시 FAIL.**
4. order-service 기존 HTTP e2e **48/48 무변경**(consumer 루프는 HTTP path와 독립; --with-kafka 시에만 동작).

### 2.7 TDD (B)
- B1-1 red: `KafkaListenerIndexerTest`(@KafkaListener 픽스처 소스 → KafkaConsumer; 리터럴 토픽 + `${prop}` 미해석 skip). green: indexer.
- B1-2: `KafkaCaptureRunner` — **폴링 종료 로직만 격리 단위테스트**: `SutProcess`/`CoverageClient`를 stub(logOffset 고정·지정 delay 후 SQL 출현 흉내; coverage delta empty/branch). producer는 Testcontainers Kafka(또는 발행 자체는 통합으로). 결선은 실제 notification 빌더 통합으로 검증(§2.6-1).
- B2(조건부): generator 단위(KafkaExchange→소스) + e2e 가드.

---

## 3. 범위/비범위
- **범위**: A 전체; B1(빌더-side, notification/analytics consumer 커버 + bonus read); order-service Kafka 회귀 가드.
- **조건부(사용자 승인)**: B2(Kafka 테스트 생성). e2e 비용·async 안정성 평가 후.
- **비범위**: counseling(WebFlux 0 HTTP 엔드포인트 — 단 @KafkaListener는 보유, B1에서 인덱싱은 가능하나 후순위)·bff-gateway. publish-only(community/diary): `@KafkaListener` 없음(검증됨, grep) → consumer 범위 밖. 발행 코드는 HTTP write 경로에서 이미 커버.

## 4. 회귀 가드 (order-service, SUT 코드 변경 포함)
- **A**: order-service 기존 Booking 생성(다중 검증 보유)에서 제약-aware happy가 유효 201 — `BuilderE2eTest`에 create 성공경로 단언.
- **B**: **order-service 소스에 신규 `@KafkaListener` 추가**(본 브랜치 작업): `@KafkaListener(topics="order.events")` → consumer가 `INSERT INTO order_events(...)`. 이 consumer는 HTTP path 탐색 루프와 **독립**이므로 기존 48 HTTP e2e에 영향 0; `--with-kafka` 없으면 인덱싱은 되나 발행 안 함(또는 빈 루프). `BuilderE2eTest`(`--with-kafka` 기동)가 §2.6-3 단언.

## 5. 리스크 (정정)
- **Kafka async 비결정성**: 폴링 종료조건(SQL 출현 1차 / coverage delta 2차) + 8초 타임아웃 + skip-on-timeout. no-op consumer와 false-negative 구분 불가 → 커버 0 유지(회귀 아님).
- **순서/공유 상태**: Kafka-먼저 순서 + 공통 ProbeId로 read bonus 보장; 깨져도 best-effort(회귀 아님).
- **토픽 prop**: application.yml 해석, 실패 시 consumer skip.
- **dedup 결정성**: run-scoped eventId prefix.
- **A 한계**: inter-field 가드는 happy로 못 풀어 일부 throw 잔존 — 단일필드는 개선, 회귀 아님.
- **B2 e2e Kafka broker**: testlib/e2e에 broker 의존 추가 — 조건부 단계에서 평가.

## 6. 관련 파일
- A: `run/SampleInputSynthesizer`(+오버로드), `run/EndpointExplorationRunner`(happyInput 시그니처+배선), `index/ValidationConstraintExtractor`(재사용), 테스트 `SampleInputSynthesizerTest`.
- B: 신규 `index/KafkaListenerIndexer`, `run/KafkaCaptureRunner`, model `KafkaConsumer`/`KafkaExchange`, `env/AnalysisEnvironment`(bootstrap getter), `cli/BuilderCli`(배선), (B2) `test-generator/Generator`+`kafka-test-class.mustache`+`testlib/KafkaHelper`. 참조: `WsEndpointIndexer`/`WsCaptureRunner`/`StompHelper`(패턴).
- 회귀: `samples/order-service`(@KafkaListener + order_events), `graph-rag-builder/.../BuilderE2eTest`.
