# 의사결정: Phase 0 구조 인덱싱은 Spoon 단독

> **⚠️ SUPERSEDED (2026-06-14)** — 구조 인덱싱(엔드포인트/바디shape/제약)은 여전히 Spoon이지만,
> 입력 발견(`oracle/ConcolicOracle`)을 위해 빌더가 **ASM + Z3(`tools.aqua:z3-turnkey`)**도 의존함
> (bootJar 바이트코드 심볼릭 분석). 이 문서의 "복귀 조건"(Phase 1 진입 시 재평가)이 실현된 것.
> 현행: [docs/24](../24-exploration-backends-and-input-oracle.md). 이 문서는 당시 결정 기록으로 보존.

날짜: 2026-06-10 / 단계: 0.6

## 원안 (docs/02, 03)

L1 = scip-java(타입 해석 심볼 그래프) + Spoon(AST enrichment).

## 결정

Phase 0은 **Spoon(noClasspath 모드) 단독**으로 인덱싱한다.

## 근거

- Phase 0 요구는 "@RestController/@PostMapping/@RequestBody 1개 endpoint 식별 +
  body 타입 필드 추출"이 전부 — 어노테이션/시그니처 수준이며 cross-file 심볼
  해석이 불필요.
- scip-java는 SUT의 빌드 시스템(Maven/Gradle) 통합 실행이 필요해 인프라 비용이
  크다. 이전 시도(docs/22)도 같은 이유로 AST-only로 후퇴했다.
- noClasspath 모드라 SUT 의존성 해석 없이 동작 — 빌더가 SUT 빌드에 비의존.

## 한계와 복귀 조건

- 호출 그래프/데이터플로우가 필요한 Phase 1(분기 탐색)·Phase 2(HTTP 호출 추적)
  진입 시 scip-java 또는 Spoon classpath 모드 재평가.
- docs/22의 정적 분석 한계(파생 쿼리, DI-by-interface 등)는 동일하게 적용되며,
  실행 캡처(L3/L4)가 보완한다는 원안의 전제도 동일.
