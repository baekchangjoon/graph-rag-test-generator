# P4 — 탐색 상태의 결정적 재현 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`. 생성물은 **런타임 실행 테스트**로 검증(P3 교훈). OS-하드코딩 금지(gateway-CI 교훈).

**Goal:** 탐색 시점 DB 오염으로 캡처된 비-2xx 상태(예: 500)가 빈 DB 재현 시 다른 상태(404)로 갈려 생성 테스트가 격리되는 문제(G4)를 없앤다 — **재현 가능한 상태만 테스트로 승격**한다.

**Architecture:** 별도 generator 변경 없음. builder(`EndpointExplorationRunner`)가 캡처한 path 중 **빈 DB + 선언 requiredSeeds만으로 재현 불가한 비-2xx path를 `ExploredPath`로 기록하지 않고**, 드롭 사실을 `ExplorationReport`(→ `exploration-report.json`)에 남긴다.

**REQ:** REQ-013(지배 불변식)·REQ-014(억제, builder)·REQ-015(가시성). 모두 Must.

> 출처: RFC §5 P4(C6 지배 불변식), 요구사항 REQ-013~015. 우선순위 RFC §7(P4 = 4순위).

## 핵심 설계 결정 (재현성 판정 — 가장 미묘한 부분)
**지배 불변식(REQ-013):** 생성 테스트의 expected status는 *빈 DB + 그 테스트가 선언한 requiredSeeds*만으로 결정적으로 재현 가능해야 한다.

**판정 방법(권장 — 휴리스틱이 아닌 실제 검증):** 비-2xx path를 **clean-DB replay로 실제 재현 검증**한다. 탐색이 끝난 뒤(또는 path별로), 빈 DB에 그 path의 선언 requiredSeeds만 적용하고 동일 입력으로 재요청 → 캡처 status와 일치하면 재현 가능(KEEP), 불일치(예: 캡처 500 vs replay 404)면 재현 불가(SUPPRESS + 로그). 이는 G4-(b)의 "탐색-오염 500"을 정확히 잡는다(휴리스틱 "5xx-no-seeds 일괄 제외"는 입력 유래 진짜 버그 500을 오제거하므로 부적합 — REQ-015 "진짜 버그 은폐 금지"와 충돌).

**판정 기준(요약):** 비-2xx(4xx/5xx) path에 대해 clean-DB+선언시드 replay status == 캡처 status면 KEEP, 아니면 DROP. 2xx는 기존 attachSeeds가 시드 보장(불변). 4xx(검증/not-found)는 보통 입력/빈-DB로 재현되므로 대부분 KEEP된다.

> **확인 필요(구현 시):** ① 기존 pass-1/pass-2(`EndpointExplorationRunner` L205~249)·`deleteSeeds`/`reinsertSeeds`로 clean-DB replay를 구현할 수 있는지(시드 삭제→선언시드만 재적용→재요청) ② replay 비용(엔드포인트당 비-2xx path 수 — budget 영향) ③ replay가 부작용(쓰기) path엔 부적합 → 비-2xx & 부작용 없는 path로 한정하거나, 쓰기 path는 별도 처리.

## 현황(실코드, off main 0073dbe)
- path는 `out.status()`/`candidate.status()`를 expectedStatus로 캡처, requiredSeedIds=`List.of()` 초기화 후 `attachSeeds`(L586~)가 GET 첫 2xx/by-id에 시드 부착.
- `ExplorationReport`(shared-model) + `exploration-report.json` 작성(BuilderCli L277). REQ-015 droppedPaths 추가 지점.
- s500_1류: GET 비-2xx + requiredSeedIds=[] + 탐색-오염 → 재현 시 status 갈림.

---

## Task 1: clean-DB replay 재현 검증 메커니즘 (REQ-013)
**REQ-IDs:** REQ-013
- [ ] builder에 "path를 빈 DB + 선언 requiredSeeds로 replay해 status를 얻는" 헬퍼 추가(기존 `deleteSeeds`/`reinsertSeeds` + 요청 전송 재사용). 부작용 없는(GET 등) 비-2xx path에 적용. 단위/통합 TDD: 오염 상태에서 캡처한 500이 clean replay에서 404가 나옴을 검증(s500_1 재현 fixture).

## Task 2: 비재현 비-2xx path 억제 (REQ-014)
**REQ-IDs:** REQ-014
- [ ] attachSeeds 이후 path 최종화 단계에서, 비-2xx path에 대해 Task1 replay 검증 → 캡처 status와 불일치면 `ExploredPath`로 기록하지 않음(builder 억제; generator는 무변경). 2xx·재현일치 path는 유지. TDD: 오염-500 path가 그래프에 미기록됨.

## Task 3: 드롭 가시성 (REQ-015)
**REQ-IDs:** REQ-015
- [ ] `ExplorationReport`에 `droppedPaths`(path id + endpoint + 캡처status + replay status + 사유) 필드 추가(compat). 억제 시 로그(`log.warn`) + report 기록. TDD: 드롭 발생 시 exploration-report.json의 droppedPaths에 항목 + 카운트.
- **REQ-015 불변:** 판정 기준은 "재현 가능하냐"(버그 여부 아님) — 빈 DB+시드로 재현되는 5xx 진짜 버그는 KEEP. 드롭은 로그로 표면화해 은폐 금지.

## Task 4: 내부 fixture E2E + 회귀 + 매트릭스
**REQ-IDs:** REQ-013/014/015
- [ ] 오염-가능 GET 경로를 가진 내부 fixture(또는 기존 e2e 확장)로 "캡처-재현 상태 일치 or 드롭" 실증(빈 단언 금지, 런타임 실행). `:graph-rag-builder:test`+`e2e/run-e2e.sh` green. 매트릭스 REQ-013~015 🟢, Coverage 갱신.

---

## Self-Review(작성자)
- Spec coverage: REQ-013(T1)·REQ-014(T2)·REQ-015(T3)·검증(T4). 누락 없음.
- 설계 결정: 휴리스틱이 아닌 **실제 clean-DB replay 검증**(진짜 버그 오제거 방지). 부작용 path 한정.
- **확인 필요:** replay 구현 가능성·비용·부작용 path 처리(Task1) — 구현 시 확정.
- 런타임 검증 의무(P3 교훈) + OS-이식성(gateway-CI 교훈).

## Execution
Subagent-driven, task별 spec+quality 리뷰 + 런타임 검증. CI watch → rebase 머지. (REQ-016 cross-endpoint는 🔵 연기 — 별개.)
