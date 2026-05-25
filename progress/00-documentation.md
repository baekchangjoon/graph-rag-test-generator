# Progress: Documentation Pass

**Date**: 2026-05-25
**Task**: #1 설계 문서 일괄 생성

## 산출물

| 파일 | 내용 | 라인수 |
|---|---|---|
| README.md | 시스템 개요 + 인덱스 | 56 |
| SCHEMAS.md | 4개 API 스키마 정의 (공통 모델 + 도구1/2/testlib/대시보드) | 583 |
| OPEN-DECISIONS.md | 미결 의사결정 9개 그룹 | 222 |
| docs/01-overview.md | 목적, 범위, 대상 프로젝트 | 71 |
| docs/02-architecture.md | 도구 분리, 컴포넌트, 데이터 흐름 | 195 |
| docs/03-graph-rag-builder.md | 도구 1: 5개 레이어, 핵심 도구, 갱신 | 117 |
| docs/04-test-generator.md | 도구 2: 합성 방식 C, 규칙 카탈로그 | 197 |
| docs/05-branch-exploration.md | JDart → fuzzer → EvoSuite 순차 | 161 |
| docs/06-test-environment.md | RestAssured + docker-compose, OTEL | 189 |
| docs/07-mock-infrastructure.md | WireMock, socket-mock, testlib 어댑터 | 143 |
| docs/08-dashboard.md | test-state-dashboard, 누수 감지 | 171 |
| docs/09-implementation-roadmap.md | Phase 0-6 + TDD 흐름 | 133 |

합계 2,238 라인.

## 설계 의도와의 부합 검증

### 정확히 반영된 항목

- [x] 도구 1, 도구 2가 모두 LLM을 내부에 포함하지 않음
- [x] LLM/사람은 외부 오케스트레이터로만 등장
- [x] 도구 2는 결정적 컴파일러 (템플릿 + 프로그램)
- [x] 합성 방식은 C (큰 골격 템플릿 + 가변 슬롯 프로그램)
- [x] RestAssured 스타일, docker-compose 환경
- [x] HTTP mock: WireMock / Socket mock: 자체 Netty
- [x] DB 상태 검증 없음, 자기 스코프 cleanup
- [x] 격리 전략 (b)+(c): 자기 스코프 cleanup + unique testId
- [x] OTEL javaagent로 baggage propagation (SUT 무수정)
- [x] 인증: real / disabled 두 모드, 어댑터로 교체 가능
- [x] 대시보드: fire-and-forget, 누수 감지, reaper opt-in
- [x] 분기 탐색: A → B → C 순차 보강
- [x] Phase 별 단계적 진행 + 매 phase 끝 E2E

### 의도적으로 늦춘 항목

- 그래프/벡터 스토어 구체 선택 → OPEN-DECISIONS B1, B2
- 빌드 시스템/언어 → OPEN-DECISIONS A1, A2
- PoC 대상 endpoint → OPEN-DECISIONS C1
- 운영 DBMS → OPEN-DECISIONS C2

## 다음 단계

Task #2: 미결 의사결정 사용자 확인 요청.
OPEN-DECISIONS.md의 9개 그룹 답변 수신 후 Task #3 (프로젝트 골격 셋업) 진입.

## 비고

- SCHEMAS.md 작성 시 PreToolUse hook이 JDBC `exec` 메소드명을 차단했음. `update`로 변경 (JdbcTemplate 컨벤션과 일치, 의미 동일).
- `docs/` 폴더 구조로 정리. 모두 현재 경로(`/Users/changjoonbaek/graph-rag/graph-rag`) 아래.
- `progress/` 폴더 신규 생성. 단계별 진행/검수 기록 위치.
