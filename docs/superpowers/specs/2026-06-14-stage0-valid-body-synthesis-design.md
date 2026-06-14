# Stage 0 — 유효 입력값 합성 (enum/날짜/이메일) — 설계

작성일: 2026-06-14 (v2 — opus/sonnet/haiku 검토 반영)
관련: docs/24(탐색 백엔드), docs/25(이론), petclinic boarding 실측

## 배경 / 문제 (실측)

spring-petclinic `boarding`에 빌더 적용 시 핸들러까지 도달(auth 정상)하나 **항상 400**이고 service
검증 분기를 못 연다. 1차 원인은 합성된 입력의 무효값:

- `SampleInputSynthesizer.putScalar`가 **enum**(`PriceTier`)에 `"sample-priceTier"`, **날짜**
  (`LocalDate checkInDate`)에 `"sample-checkInDate"`를 넣음 → SUT Jackson **역직렬화 실패/null** →
  `priceTier==null`/날짜 파싱 실패로 service 초기에서 400. 즉 **검증 분기에 진입조차 못 함**.
- GET `/api/reservations`의 enum QUERY 파라미터(`tier`)도 `ReadInputSynthesizer.scalarFor`가
  `"probe-tier"`로 합성 → Spring MVC enum 변환 실패로 400.

→ deep 검증의 1차 관문은 **"유효하게 역직렬화/바인딩되는 입력"**. 이것을 풀면 happy/변이가 **단변수
검증 가드**에 도달하고, 기존 오라클 변이가 그 가드들을 flip할 수 있다.

## 목표

합성 입력(POST/PUT body + GET query param)이 **enum/날짜/이메일** 필드에 **유효 값**을 갖게 하여
역직렬화/바인딩이 성공, service 검증 로직에 진입하도록 한다.

## 비목표 (명시 — 검토 반영)

- **다변수 가드는 Stage 2**: `priceTier==VIP && loyaltyPoints<500`, `deposit*1.1 < nights*rate`
  처럼 2개+ 필드가 동시에 특정 조합이어야 열리는 분기는 이번에 **안 열림**. happy 단변수 walk 한계.
- **enum 대체값 변이 없음**: `InputMutator`는 NUMERIC/String 필드만 변이(`InputMutator.java`의
  `firstOrder`/`constraintDirected`). enum/date 필드는 remove/null만. 따라서 `priceTier`를 BASIC↔VIP로
  바꿔보는 변이는 이번 범위 밖 → enum 값에 따라 갈리는 분기는 happy 고정값(첫 상수)에서만 평가됨.
- 정규식 일반 문자열 생성(Z3 string), `floorMod(hashCode,7)==3`(불투명), 숫자 happy 기본값의 in-range
  자동 추론(오라클 변이가 per-field 처리) — 모두 범위 밖.

## 설계

### 1. enum 상수 추출 — 새 `EnumConstantExtractor` (Spoon, 자체 모델 1회 빌드)

`io.graphrag.builder.index.EnumConstantExtractor`:
`Map<String,List<String>> extract(Path srcDir)`. 자체 `Launcher`(noClasspath, complianceLevel 17)로
모델 빌드 후 `model.getElements(new TypeFilter<>(CtEnum.class))`로 모든 enum(중첩 포함) 순회 →
**키 = `ctEnum.getQualifiedName()`**, 값 = `ctEnum.getEnumValues()`의 `getSimpleName()` 목록(선언 순서).
- 키 포맷 근거: `BodyShapeExtractor`가 `BodyField.javaType`에 쓰는 것도 동일 `getQualifiedName()`이므로
  포맷 일치(둘 다 `.` 구분, 중첩 enum도 동일). 별도 Spoon 빌드는 기존 extractor 패턴과 동일(비용 감수).

### 2. `SampleInputSynthesizer` — enum 맵 주입 + `putScalar` 확장

- **필드 추가**: `private final Map<String,List<String>> enumConstants;`
- **생성자 2개**: `SampleInputSynthesizer()` → 빈 맵(기존 호출부 호환), `SampleInputSynthesizer(Map<...>)`.
- `synthesize(BodyShape, List<TableSchema>)` 시그니처 **불변**.
- `putScalar(body, field)` switch **우선순위(위→아래, 첫 매치)**:
  1. 기존 정수/실수/불리언 → 현행(1 / 1.0 / true)
  2. `field.javaType()` 이 `java.time.LocalDate` → `"2999-01-01"`; `LocalDateTime` →
     `"2999-01-01T00:00:00"`; `LocalTime` → `"00:00:00"`; `Instant`/`OffsetDateTime`/`ZonedDateTime` →
     `"2999-01-01T00:00:00Z"`
  3. `enumConstants`에 `field.javaType()`이 키로 존재 → **첫 상수**(string). 키 미스 시
     **simple-name 폴백**(맵 키들의 `lastIndexOf('.')` 뒤가 field.javaType의 simple name과 일치하면 사용)
  4. `field.name().toLowerCase().endsWith("email")` → `"probe@example.com"` (petclinic 정규식
     `^[\w.+-]+@[\w-]+\.[\w.]+$` 통과 확인)
  5. 그 외(String 등) → 현행 `"sample-" + name`
- 모든 값은 JSON 문자열로 put → SUT Jackson이 string→enum/LocalDate 역직렬화(표준). 빌더 측 직렬화는
  이미 문자열이라 java-time 모듈 불요.

### 3. `ReadInputSynthesizer.scalarFor` — query/path param enum·날짜 처리

GET read-path도 같은 enum 맵을 받아 `scalarFor(param, probeId)`에:
- `param.javaType()` 이 enum 키면 첫 상수, `java.time.LocalDate` 등이면 위 날짜 문자열.
- 그 외 현행(정수→probeId, default→`"probe-"+name`).
- `ReadInputSynthesizer`도 enum 맵 필드 + 생성자 2개(빈 맵 기본).

### 4. 배선 (단일 방식 — 검토 반영)

- `BuilderCli.build()`의 인덱싱 직후(엔드포인트 루프 진입 전, `allComparisons` 추출부 근처) 1회:
  `Map<String,List<String>> enumConstants = new EnumConstantExtractor().extract(config.sutSrc());`
- `EndpointExplorationRunner` 생성자에 `Map<String,List<String>> enumConstants` 파라미터 추가 →
  `run()` 내부에서 `new SampleInputSynthesizer(enumConstants)` / `new ReadInputSynthesizer(enumConstants)`.
- `BuilderCli`에서 runner 생성 시 enumConstants 전달.
- **`WsCaptureRunner`는 이번 범위 밖** — `new SampleInputSynthesizer()`(빈 맵) 유지(WS payload enum은
  추후). 명시적 비목표.

## 측정 (검토 반영 — 매우 중요)

검증 로직은 **`ReservationService`(컨트롤러와 다른 클래스)** 에 있다. handler-method 리포트
(`EndpointExploration.coveredBranches`)는 컨트롤러 분기만 집계하므로 **service 분기 증가는
`ExplorationReport.coveredAppBranches`(whole-app, BOOT-INF/classes 전체)** 로 측정해야 한다.
(참고: 메모리 `coverage-handler-class-scoping`.)

## 결정성
enum 선언 순서 고정, 날짜/이메일 상수. Random/시간 금지(docs/04).

## 테스트
- `EnumConstantExtractorTest`: enum 픽스처(2상수 + 중첩 enum) → FQN→상수 목록(선언 순서).
- `SampleInputSynthesizerTest`(보강): `LocalDate`→ISO, enum FQN→첫상수, simple-name 폴백,
  `*Email`→`probe@example.com`, enum-아닌 타입→기존 default, 우선순위(정수가 enum보다 먼저).
- `ReadInputSynthesizerTest`(보강): enum query param → 첫상수.
- 회귀: order-service e2e 22/22 유지, petclinic builder GREEN.
- 성과(A/B): petclinic **coveredAppBranches** Stage0 전/후 비교 + `GRB_ORACLE` static/both 각각.

## 성공 기준 (검토 반영 — 정확히)
1. order-service 회귀 GREEN 유지.
2. petclinic reservations 요청이 **역직렬화/바인딩 성공으로 service 검증에 진입** — 즉 400의 성격이
   "역직렬화 실패"에서 "검증 통과/실패"로 바뀌고, **`coveredAppBranches` 증가**(특히 `ReservationService`의
   단변수 가드: nights/roomNumber/animalCount/petName length/priceTier-null). 다변수 가드
   (VIP·deposit)는 미달성 허용(Stage 2).
3. 신규 추출기/합성 규칙 단위 테스트로 결정성 보장.

## 위험과 완화
- noClasspath에서 `BodyField.javaType`이 simple name으로 떨어질 가능성 → §2.3의 simple-name 폴백으로 보완.
- 날짜 포맷이 SUT 커스텀 패턴이면 실패 → ISO-8601 표준(Spring 기본 수용). 커스텀 패턴 SUT는 범위 밖.
- 첫 enum 상수가 "가장 무난한 happy"가 아닐 수 있음 → 결정성 우선, 최적 선택은 안 함(비목표).
- 단변수 walk가 깊은 다변수 가드 앞에서 멈춤 → 의도된 한계(Stage 2). 성공 기준 2가 이를 반영.
