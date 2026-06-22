# 정적 인덱싱 증분화 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-22-incremental-static-index-design.md
> 범위: C안(Stage 1 모델 공유 + Stage 2 전체모델 1회 + 조각 캐시). B안(부분모델)은 제외(🔵).
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)

---

## 요구사항 목록

### REQ-001 — 정적 인덱싱 블록의 단일 Spoon 모델 빌드
- 유형: Non-functional (성능)
- 우선순위: Must
- 설명: 한 번의 `build()` 정적 인덱싱 블록(진입~explore 직전)에서 Spoon `buildModel()`을 1회만
  수행하고, 7개 Spoon 인덱서가 그 모델을 공유한다.
- 수용기준:
  - Given 임의의 SUT 소스, When `build()`의 정적 인덱싱 블록을 1회 실행하면,
    Then `SharedSpoonModel.buildCount`가 정확히 1이다.
- 검증 레벨: integration

### REQ-002 — Stage 1 결과 동등성
- 유형: Functional
- 우선순위: Must
- 설명: 단일 공유 모델로 생성한 정적 인덱싱 산출물(`graph.json`)이 기존(인덱서별 개별 모델)
  방식의 결과와 동일하다.
- 수용기준:
  - Given 동일 SUT, When 공유-모델 빌드와 기존-방식 빌드를 각각 수행하면,
    Then 두 `graph.json`의 정적 인덱싱 산출물(endpoints/ws/kafka/mappers/dto/enum)이 동일하다.
- 검증 레벨: E2E black-box (golden 비교)

### REQ-003 — 무변경 재빌드 시 Spoon 0회 + 동일 결과
- 유형: Functional
- 우선순위: Must
- 설명: 소스가 변경되지 않은 재빌드에서는 캐시로 전체 산출물을 복원하며 Spoon 빌드를 하지 않는다.
- 수용기준:
  - Given 1회 빌드로 캐시가 채워진 동일 SUT, When 소스 변경 없이 재빌드하면,
    Then 정적 인덱싱 블록 `buildCount == 0`이고 `graph.json`이 1회차와 동일하다.
- 검증 레벨: E2E black-box + integration(카운터)

### REQ-004 — 자기완결 단일 파일 수정 시 부분 갱신
- 유형: Functional
- 우선순위: Must
- 설명: cross-file 의존이 없는 핸들러/DTO 파일 1개만 수정하면, 그 파일의 조각만 재계산되고 나머지
  조각은 캐시에서 재사용되며 최종 그래프가 정확하다.
- 수용기준:
  - Given 캐시가 채워진 SUT, When 자기완결 파일 1개를 수정해 재빌드하면,
    Then 그 파일 조각만 갱신되고 나머지 조각은 재계산되지 않으며 `graph.json`이 풀 리빌드와 동일하다.
- 검증 레벨: E2E black-box + integration(조각 갱신 범위)

### REQ-005 — 파일 삭제 반영
- 유형: Functional
- 우선순위: Must
- 설명: 핸들러 선언 파일을 삭제하면 해당 산출물 조각이 그래프에서 제거된다.
- 수용기준:
  - Given 엔드포인트 E를 가진 캐시된 SUT, When E의 핸들러 파일을 삭제해 재빌드하면,
    Then `graph.json`에서 E 관련 산출물이 사라지고 풀 리빌드 결과와 동일하다.
- 검증 레벨: E2E black-box

### REQ-006 — 증분 결과 == 풀 리빌드 결과 (완전 동등성)
- 유형: Functional
- 우선순위: Must
- 설명: 임의의 변경 시퀀스(추가/수정/삭제, cross-file 포함) 후 캐시를 사용한 빌드 결과가
  `--no-incremental` 풀 리빌드 결과와 완전히 동일하다(C안 G4).
- 수용기준:
  - Given 여러 변경이 누적된 SUT, When 증분 빌드와 `--no-incremental` 빌드를 각각 수행하면,
    Then 두 `graph.json`이 동일하다.
- 검증 레벨: E2E black-box (golden 비교)

### REQ-007 — `--no-incremental` 플래그
- 유형: Functional
- 우선순위: Must
- 설명: `--no-incremental`(또는 `--reindex`) 지정 시 캐시를 무시하고 풀 리빌드한 뒤 캐시를 재작성한다.
- 수용기준:
  - Given 캐시가 존재하는 SUT, When `--no-incremental`로 빌드하면,
    Then 캐시를 읽지 않고 풀 리빌드하며 캐시가 새로 작성된다.
- 검증 레벨: E2E black-box

### REQ-008 — 스키마 버전 불일치 시 자동 풀 리빌드
- 유형: Functional
- 우선순위: Must
- 설명: manifest의 `schemaVersion`이 현재 빌더 상수와 다르면(상위/하위 무관) 캐시를 폐기하고
  풀 리빌드한다.
- 수용기준:
  - Given `schemaVersion`이 불일치하는 manifest, When 재빌드하면,
    Then 캐시를 무시하고 풀 리빌드하며 새 버전으로 캐시를 재작성한다.
- 검증 레벨: integration

### REQ-009 — XML(mapper) 변경 반영
- 유형: Functional
- 우선순위: Must
- 설명: `sutResources`의 MyBatis mapper XML이 변경되면 mappers 조각이 갱신되고 그래프가 정확하다.
- 수용기준:
  - Given 캐시된 SUT, When mapper XML 1개를 수정해 재빌드하면,
    Then mappers 산출물이 갱신되고 `graph.json`이 풀 리빌드와 동일하다.
- 검증 레벨: E2E black-box

### REQ-010 — 캐시 손상/원자적 쓰기 안전성
- 유형: Non-functional (견고성)
- 우선순위: Should
- 설명: manifest/fragments 쓰기는 temp 후 atomic rename으로 수행하고, 손상된 캐시는 감지 시
  풀 리빌드로 안전하게 복구한다.
- 수용기준:
  - Given 손상된(파싱 불가) manifest, When 재빌드하면,
    Then 예외로 죽지 않고 경고 후 풀 리빌드하여 정상 `graph.json`을 산출한다.
- 검증 레벨: integration

### REQ-011 — 기존 `index(Path)` 진입점 하위호환
- 유형: Functional
- 우선순위: Must
- 설명: 각 인덱서의 기존 `index(Path)`/`extract(Path)` 시그니처와 동작이 보존된다(내부에서 모델
  빌드 후 `index(CtModel)`에 위임).
- 수용기준:
  - Given 기존 인덱서 단위 테스트, When 리팩터 후 그대로 실행하면, Then 모두 통과한다.
- 검증 레벨: integration (기존 단위 테스트 회귀)

---

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | 정적 블록 단일 모델 빌드 | `SharedSpoonModelTest#buildCountIsOnePerBlock` | integration | 🔴 planned |
| REQ-002 | Stage 1 결과 동등성 | `IncrementalIndexE2E#sharedModelEqualsLegacy` | E2E | 🔴 planned |
| REQ-003 | 무변경 0회 + 동일 | `IncrementalIndexE2E#noChangeRebuildZeroBuilds` | E2E | 🔴 planned |
| REQ-004 | 자기완결 단일 수정 부분 갱신 | `IncrementalIndexE2E#singleFileEditPartialUpdate` | E2E | 🔴 planned |
| REQ-005 | 파일 삭제 반영 | `IncrementalIndexE2E#deletedFileRemovesFragment` | E2E | 🔴 planned |
| REQ-006 | 증분 == 풀리빌드 동등성 | `IncrementalIndexE2E#incrementalEqualsFullRebuild` | E2E | 🔴 planned |
| REQ-007 | `--no-incremental` | `IncrementalIndexE2E#noIncrementalForcesFullRebuild` | E2E | 🔴 planned |
| REQ-008 | schemaVersion 무효화 | `IndexCacheTest#schemaMismatchTriggersRebuild` | integration | 🔴 planned |
| REQ-009 | XML 변경 반영 | `IncrementalIndexE2E#mapperXmlEditUpdatesFragment` | E2E | 🔴 planned |
| REQ-010 | 캐시 손상/원자적 쓰기 | `IndexCacheTest#corruptManifestFallsBackToRebuild` | integration | 🔴 planned |
| REQ-011 | `index(Path)` 하위호환 | 기존 인덱서 단위 테스트 회귀(green 유지) | integration | 🔴 planned |

Coverage: 0/11 green (0%) — target 100% (대상: Must 10 + Should 1; 모두 분모 포함). B안 관련 요구는 본 명세에 없음(🔵 범위 제외).
