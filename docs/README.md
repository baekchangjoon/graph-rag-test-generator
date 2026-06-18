# 문서 지도

처음이면 [00-시작하기](00-getting-started.md)부터. 용어가 막히면 [glossary](glossary.md).

## 무엇을 보려면 어디

| 알고 싶은 것 | 문서 |
|---|---|
| 한 번 돌려보고 내 앱에 적용 | [00-getting-started](00-getting-started.md) |
| 이 도구가 무엇을·왜 만드는가, 범위 | [01-overview](01-overview.md) |
| 두 도구가 어떻게 맞물리는가(전체 흐름) | [02-architecture](02-architecture.md) |
| 도구 1(사실 캡처)의 내부 | [03-graph-rag-builder](03-graph-rag-builder.md) |
| 도구 2(테스트 합성)의 내부 | [04-test-generator](04-test-generator.md) |
| 생성된 테스트가 실행되는 환경 | [06-test-environment](06-test-environment.md) |
| WireMock·socket-mock 등 mock 인프라 | [07-mock-infrastructure](07-mock-infrastructure.md) |
| 테스트 자원 추적·누수 감지 대시보드 | [08-dashboard](08-dashboard.md) |
| phase별 진행과 다음 단계 | [09-implementation-roadmap](09-implementation-roadmap.md) |
| 500만 라인 레거시 적용 전략 | [10-legacy-scaling](10-legacy-scaling.md) |
| 정적 분석만으로 못 찾는 입력의 한계 | [22-static-discovery-limits](22-static-discovery-limits.md) |
| 입력을 어떻게 만들어 분기를 여는가 | [23-input-generation-flow](23-input-generation-flow.md) |
| 탐색 엔진과 입력 오라클(ASM+Z3) | [24-exploration-backends-and-input-oracle](24-exploration-backends-and-input-oracle.md) |
| 입력 발견의 이론적 근거 | [25-input-discovery-theory](25-input-discovery-theory.md) |
| 사용자 compose로 SUT 분석(attach 모드) + 커스텀 요청 헤더 | [26-attach-mode](26-attach-mode.md) |
| SQL 캡처 모드(OTEL 기본 / log 폴백) | [06-test-environment](06-test-environment.md) "SQL 캡처 모드" 절 |
| 로드맵: OTEL SQL 캡처(완료)·외부 stub seeding·override 키 경고 | [27-roadmap-otel-capture-stub-seeding](27-roadmap-otel-capture-stub-seeding.md) |
| 기능별 설계 결정 기록 | [decisions/](decisions/) |
| 개발 내력(과거 시점 스냅샷) | [archive/](archive/) |

## 읽는 순서 (권장)

1. [00-getting-started](00-getting-started.md) — 직접 돌려보기
2. [01-overview](01-overview.md) → [02-architecture](02-architecture.md) — 무엇을·왜, 전체 그림
3. 깊이 들어갈 때: 도구 1은 [03](03-graph-rag-builder.md), 도구 2는 [04](04-test-generator.md)
4. 입력 생성이 궁금하면: [23](23-input-generation-flow.md) → [24](24-exploration-backends-and-input-oracle.md) → [25](25-input-discovery-theory.md)

## 번호에 빈 곳이 있는 이유

문서 번호는 주제별로 매겨졌고 연속이 아니다. 빠진 번호는 다른 문서로 통합되었거나
넘버링을 다시 정리하면서 비었다. 개발 과정의 시점 기록은 [archive/](archive/)에 따로
있다(번호 없는 스냅샷). 현재 유효한 문서는 위 표가 전부다.
