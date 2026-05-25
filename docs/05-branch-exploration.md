# 05 — 분기 탐색 전략

도구 1의 Layer 3. 주어진 endpoint에서 도달 가능한 코드 경로를 발견하고, 각 경로에서 발생하는 외부 의존(SQL, HTTP, Socket)을 캡처한다.

## 세 엔진의 순차 보강

```
[Phase A] JDart (콘콜릭 실행, 1차)
            ↓ coverage 누적
[Phase B] coverage-guided fuzzer + JaCoCo (2차)
            ↓ A가 놓친 분기 타겟
[Phase C] EvoSuite bridge (3차)
            ↓ 잔여 분기에 대해서만 활성
[merged paths + coverage report]
```

각 엔진은 동일 SPI를 구현하고, 결과는 동일한 `ExploredPath` 모델로 통합된다.

## SPI

```java
interface PathExplorer {
    String name();
    ExplorationResult explore(EndpointTarget target,
                              ExplorationBudget budget,
                              KnownCoverage known);
}

class ExplorationResult {
    List<ExploredInput> inputs;          // 각 입력 + 도달한 분기
    Optional<PathConstraint> constraint; // 엔진별로 채워질 수도, 비어있을 수도
    JaCoCoCoverage coverage;
}
```

## Phase A: JDart (콘콜릭)

- 합성 입력으로 SUT를 실행하며 symbolic 변수 누적
- 각 분기에서 SMT (Z3) 호출해 다른 경로 유도 입력 생성
- 결과: 입력 + path constraint + 도달 분기

장점:
- path constraint를 직접 제공. 도구 2의 unique ID 치환에 정확한 제약 정보.
- 이론적으로 깨끗

한계:
- Spring full context와의 궁합이 검증되지 않음
- 5M 라인 풀스택에서의 실제 운영 사례 부족
- Z3 string theory의 한계

대응:
- 분석 환경에서 Spring Boot TestContext를 부팅한 상태에서 JDart 실행
- 시간 예산 cap (per endpoint), 초과 시 Phase B로 양도
- 실패 시에도 부분 결과 활용

## Phase B: Coverage-guided fuzzer

```
[입력 생성기] (heuristic + grammar-aware for JSON body)
    ↓
[Spring Boot 환경에서 endpoint invoke]
    ↓
[JaCoCo: 어느 분기 도달했나]
    ↓
[novelty 점수: 새 분기 도달 시 입력 keep]
    ↓
[입력 변형: 미도달 분기 근처로 진화]
    ↓
반복 until coverage saturation 또는 budget exhaust
```

장점:
- 100% 통제 가능
- Spring/JPA/MyBatis가 자연 동작 (런타임이므로)
- 자체 캡처 인프라(SQL/HTTP/socket recorder)와 결합 자연스러움

한계:
- path constraint를 직접 안 줌 (사후 추출 필요)
- heuristic 한계로 일부 분기 영원히 미도달 가능

대응:
- 미도달 분기는 Phase C로 양도
- path constraint 사후 추출: 도달한 입력 + 변수값을 Spoon AST + Z3에 제출

## Phase C: EvoSuite bridge

EvoSuite는 클래스 단위 JUnit 생성기지만 search-based input generation 능력이 강력하다. 클래스 → endpoint로 wrapping 후 input 탐색에만 활용.

장점:
- Genetic algorithm + branch coverage fitness, 깊은 분기 도달에 강함

한계:
- Wrapping 비용
- Spring 전체 context와 잘 안 맞을 수 있음 (mock 처리 필요)

대응:
- 잔여 분기 비율이 임계치 이상일 때만 활성
- 시간 예산 cap

## 오케스트레이션

```
ExplorationOrchestrator:
  budget = userConfig.totalBudget
  coverage = JaCoCo.empty()
  paths = []

  // Phase A
  resultA = jdart.explore(endpoint, budgetSlice(budget, 0.5), coverage)
  coverage.mergeFrom(resultA.coverage)
  paths.addAll(resultA.paths)

  // Phase B
  uncovered = AllBranches - coverage.reached()
  resultB = fuzzer.explore(endpoint, budgetSlice(budget, 0.3),
                            targetBranches: uncovered)
  coverage.mergeFrom(resultB.coverage)
  paths.addAll(resultB.paths)

  // Phase C
  stillUncovered = AllBranches - coverage.reached()
  if (stillUncovered.ratio() > threshold):
      resultC = evosuite.explore(endpoint, budgetSlice(budget, 0.2),
                                  targetBranches: stillUncovered)
      coverage.mergeFrom(resultC.coverage)
      paths.addAll(resultC.paths)

  return paths, coverage
```

각 `ExploredPath` 노드에는 `discovered_by` 메타데이터를 보존 (디버깅/품질 분석).

## OTEL javaagent와 결합

분석 환경에서 SUT 부팅 시 OpenTelemetry javaagent를 함께 부착해, 모든 외부 호출에 baggage가 자동 propagate되도록 한다. 이로 인해 분석 phase에서도 mock 격리가 자연스럽게 동작.

```yaml
# 분석 환경 설정
JAVA_TOOL_OPTIONS: -javaagent:/agents/opentelemetry-javaagent.jar
OTEL_TRACES_EXPORTER: none
OTEL_PROPAGATORS: tracecontext,baggage
OTEL_INSTRUMENTATION_*: 필요한 것만
```

## Self-check 위치

분기 탐색의 결과(captured 사실들)는 도구 1이 자체적으로 검증한다:

- 캡처된 SQL이 JSqlParser로 파싱되는가
- HTTP body가 JSON으로 valid한가
- Socket byte stream의 길이/구조가 일관되는가

검증 실패 시 ExploredPath 노드에 `validation_warnings` 첨부.

## 한계와 미해결 영역

- **리플렉션 사용 코드**: JDart, fuzzer 모두 약함. 일부 path 누락 가능.
- **Spring `@Conditional` 빈**: profile 의존이라 일부 분기 분석 환경에서만 도달.
- **시간/난수 의존 분기**: `Clock.fixed`, seeded Random으로 분석 환경 통제 필요.
- **MyBatis `<foreach>` 의 실제 카디널리티**: 분석 환경에서 1, 2, N의 대표 케이스만 시도.
- **외부 응답 enum/range**: 임베디드 mock의 minimal valid에서 출발, 실패 시 LLM 오케스트레이터가 외부에서 보강 신호.
