# 2026-06-10 — Phase 1 설계: 분기 탐색 + MyBatis

기준 문서: docs/09(1.1~1.5), docs/05(엔진 오케스트레이션·예산·포화),
docs/22(still_missing·manual seed), docs/11(캡처 방식 비교).

## 목표 (roadmap 09)

같은 endpoint의 **N개 path가 N개 테스트로 합성되고 통과**.
Phase 0의 "happy-path 1개" 한계를 분기 단위 다중 path로 확장한다.

## 범위

| 단계 | 내용 | 비고 |
|---|---|---|
| 1.1 | PathExplorer SPI + HeuristicExplorer + CoverageGuidedFuzzer + JaCoCo | docs/05 SPI/예산/포화 준수 |
| 1.2 | 정적 path constraint 추출 + JDart SPI 슬롯 | 콘콜릭 엔진은 의식적 보류 (아래) |
| 1.3 | MyBatis XML mapper 인덱서 | MapperStatement 사실 |
| 1.4 | MyBatis 발행 SQL 캡처 | 로그 기반 (외부 프로세스 원칙 유지) |
| 1.5 | 다중 path → 다중 테스트 합성 | path당 테스트 클래스 1개 |
| + | still_missing 리포트 + manual-paths 병합 | docs/22 escape hatch |

## 핵심 설계

### 1.1 분기 탐색 (docs/05 적용)

- **SPI** (docs/05 그대로):
  `PathExplorer { String name(); ExplorationResult explore(EndpointTarget, ExplorationBudget, KnownCoverage); }`
- **JaCoCo**: SUT 기동 시 `JAVA_TOOL_OPTIONS=-javaagent:jacocoagent=output=tcpserver`
  env 주입(무수정 원칙). 빌더가 요청 후 TCP dump(reset=true) → boot jar의
  `BOOT-INF/classes`만 분석해 SUT 분기 커버리지 계산. "이 입력이 새 분기를
  열었는가"가 novelty 신호.
- **HeuristicExplorer** (엔진 1): body 필드별 boundary-value 변형을 결정적으로
  생성 — 필드 누락, null, 빈 문자열, 0/-1, 미존재 FK 값. 이전 시도 T3 휴리스틱 계승.
- **CoverageGuidedFuzzer** (엔진 2): 발견된 path 입력을 시드로 결정적 변이
  시퀀스(필드×변이자 순서 고정, Random 금지) 반복. 새 분기 → keep.
- **오케스트레이터** (docs/05 구조): 엔진 순차 실행, `totalBudget`(요청 횟수 기반
  주예산 + 시간 cap)을 엔진별 슬라이스로 분할, 엔진 간 coverage 누적 전달.
  **포화 감지**: 연속 K회(기본 8) 신규 분기 0 → 조기 종료.
  `discovered_by` 메타데이터를 ExploredPath에 보존.
- **path 동일성**: 도달 분기 집합이 같으면 같은 path (중복 제거).
  expectedStatus가 같아도 분기 집합이 다르면 별개 path.

### 1.2 — path constraint와 JDart의 처리 (의식적 보류 포함)

- **정적 ConstraintExtractor**: Spoon으로 handler 메서드의 분기 조건식
  (`if`/삼항/`orElseThrow`)을 텍스트로 수집, 캡처된 path의 분기 시퀀스와 결합해
  `ExploredPath.constraints`(텍스트 리스트)로 첨부. 도구 2의 "constraint 충족
  범위 안에서 치환" 판단 재료.
- **JDart(콘콜릭) 엔진은 Phase 1에서 구현하지 않는다.**
  - 근거 1 (아키텍처): JDart/JPF는 in-process 심볼릭 실행 전제. 우리 분석 환경은
    운영 jar 외부 프로세스(HTTP 경계)라 결합 지점이 없다
    (docs/05도 "Spring full context와의 궁합 미검증" 인정).
  - 근거 2 (roadmap 09 위험 표): "Phase 1에서는 fuzzer 위주, JDart는 100K 검증 후
    확장"이 원안의 위험 대응.
  - 다만 docs/05의 3-엔진 구조는 **SPI + 오케스트레이터의 예산 슬라이스**로
    그대로 보존한다. 엔진 A/C 슬롯이 비어 있으면 예산이 다음 엔진으로 양도된다.
  - 복귀 조건: heuristic+fuzzer로 못 여는 등치/범위 분기가 실측에서 유의미하게
    남으면(still_missing 리포트로 측정 가능) 콘콜릭 스파이크를 별도 진행.

### 1.3/1.4 MyBatis

- SUT 확장: order-service에 MyBatis 혼재 추가 — `POST /api/orders/search`
  (body `{userId?, type?, minAmount?}`), XML mapper의 `<if>` 동적 SQL.
  레거시 A(혼재) 시나리오를 한 SUT로 재현.
- **인덱서(1.3)**: mapper XML 파싱 → `MapperStatement`(namespace, statementId,
  sqlKind, dynamic 여부, 원문) 사실 노드.
- **캡처(1.4)**: MyBatis 로거(`logging.level.<namespace>=TRACE`)를
  `SPRING_APPLICATION_JSON`으로 주입해 `==> Preparing:` / `==> Parameters:` 로그
  파싱. 인덱서가 찾은 namespace를 SutProcess 로깅 설정에 동적으로 추가.
  - docs/11의 ProxyDataSource/MyBatis Interceptor는 in-process 부착 전제라
    Phase 1에서도 보류 (외부 프로세스 원칙 유지, decision doc에 비교 기록).
  - MyBatis Parameters 로그는 `값(타입)` 형식이라 바인딩 추출 가능. origin 판정은
    Phase 0과 동일 규칙(값 매칭).

### 1.5 다중 path 합성

- `GenerationRequest.pathId`를 유지하되 **`pathId` 생략 시 endpoint의 전 path 생성**.
- path당 테스트 클래스 1개 (`<ClassName>__<pathId>` → 파일명 안전한 형태).
  근거: path마다 fixture/cleanup이 달라 클래스 단위 분리가 단순·견고.
- 404/400 path: 캡처된 SELECT가 fixture를 요구하지 않으면 fixture 0건으로 합성
  (FixtureComposer가 이미 사실 기반이라 자연 처리).
- 멱등 보장: 4xx path라도 SUT가 INSERT한 행이 있으면 cleanup 합성은 동일 규칙.

### still_missing + manual seed (docs/22)

- 탐색 종료 후 `exploration-report.json`: endpoint별 도달/미도달 분기 수,
  미도달 분기의 소스 위치(class/method/line), discovered_by 통계.
- `--manual-paths <dir>`: 수동 작성한 ExploredPath JSON을 그래프에 병합
  (docs/22의 Manual-Archive Seed escape hatch). path id 충돌 시 수동본 우선.

### 보류 (Phase 1 범위 외, 결정 기록)

- graph store는 JSON 파일 유지 (100K 규모 충분; Neo4j는 Phase 6 전 재평가)
- 도구 1 HTTP query API → Phase 2 (WireMock 통합과 함께 필요해지는 시점)
- EvoSuite bridge(엔진 C), OTEL javaagent 분석환경 부착(Phase 2),
  JSqlParser self-check(경량 검증으로 대체: placeholder/바인딩 수 일치 확인)

## 성공 기준

- [ ] `POST /api/orders` (JPA): 휴리스틱+퍼저가 201/404/400 path를 발견,
      각각 테스트로 합성되어 compose 환경에서 **3/3 통과**
- [ ] `POST /api/orders/search` (MyBatis): 동적 SQL 분기 path 2개 이상 발견·캡처,
      테스트 합성·통과
- [ ] exploration-report.json에 미도달 분기 리포트 산출
- [ ] 결정성: 같은 SUT/예산 설정으로 2회 실행 시 같은 path 집합
- [ ] 기존 Phase 0 E2E 비회귀, `gradlew check` GREEN
