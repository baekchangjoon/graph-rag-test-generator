# 의사결정: Phase 1 탐색 엔진 구성과 JDart 보류

날짜: 2026-06-10 / 단계: 1.1, 1.2

## 원안 (docs/05)

3-엔진 순차 보강: JDart(콘콜릭, 1차) → coverage-guided fuzzer(2차) → EvoSuite(3차).
오케스트레이터가 totalBudget을 슬라이스로 분할, coverage 누적 전달, 포화 감지.

## 결정

### 구현한 것 (docs/05 구조 준수)

- `PathExplorer` SPI + `ExplorationOrchestrator`: 엔진 순차 실행, 예산 분할
  (첫 엔진 cap=총예산 절반, 미사용분 다음 엔진 양도), 엔진 간 `KnownCoverage`
  누적, 분기 집합 기준 path dedupe, `discovered_by` 보존
- 엔진 1 `HeuristicExplorer`: happy + 1단 boundary-value 변형 (필드 누락/null/
  빈문자열/0/-1/미존재 FK) — 결정적 순서, Random 금지
- 엔진 2 `CoverageGuidedFuzzer`: novelty 입력을 시드 큐에 환류해 2단+ 조합 변이,
  연속 8회 novelty 없음 → 포화 종료
- 커버리지 신호: JaCoCo agent(tcpserver)를 SUT에 env로만 부착, 요청 단위
  dump(reset) → "이 입력이 새 분기를 열었는가"
- `ConstraintExtractor`(1.2의 제약 산출 대체): Spoon으로 handler 분기 조건식을
  정적 수집, path의 도달 분기 라인과 매칭해 `ExploredPath.constraints`로 첨부

### 보류한 것

- **JDart(콘콜릭) 엔진**: 미구현. SPI 슬롯은 열려 있음.
  - JDart/JPF는 in-process 심볼릭 실행 전제. 우리 분석 환경은 운영 jar
    **외부 프로세스**(HTTP 경계)라 결합 지점이 없다 — docs/05 스스로 "Spring full
    context 궁합 미검증"을 한계로 인정.
  - roadmap 09 위험 표의 원안 대응도 "Phase 1은 fuzzer 위주, JDart는 100K 검증
    후 확장".
  - 복귀 조건: exploration-report의 still_missing에 등치/범위 분기가 유의미하게
    누적되면 콘콜릭 스파이크를 별도 과제로 진행.
- **EvoSuite(3차)**: 잔여 분기 임계치 조건부 활성이 원안 — still_missing 데이터가
  쌓인 후 판단.

## 분기 식별의 단순화 (기록)

JaCoCo는 라인별 분기 "개수"(covered/missed)만 제공하므로 BranchRef를
`(class, method, line, k)` k<covered 형태의 단조 집합으로 구성했다.
novelty 판정·still_missing 집계에 충분하며 결정적이다. 개별 분기의
true/false 방향 식별이 필요해지면 ASM 수준 분석으로 강화한다.
