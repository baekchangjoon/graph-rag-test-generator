# 정적 인덱싱 증분화 설계 (graph-rag-builder)

- 작성일: 2026-06-22
- 대상: `graph-rag-builder` 도구 1 (`BuilderCli.build()`)의 **정적 인덱싱** 단계
- 배경: 대규모 SUT(약 500만 라인)에서 매 빌드마다 전체 소스를 재파싱하는 비용이 부담스럽다.
- 관련: 기존 증분 **탐색**(`--incremental-base` / `--changed-files`, `IncrementalBuildPlanner`)은
  런타임 탐색 단계 전용이며, 본 설계의 정적 인덱싱 캐시와 독립적이다.

---

## 1. 문제 정의

`BuilderCli.build()`(graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java:162)는
호출 시마다 다음 인덱서를 **각각 독립적으로** 실행한다. 각 인덱서는 자체적으로
`new Launcher().addInputResource(sutSrc).buildModel()`을 수행한다.

build() 진입 시 직접 호출되어 SUT 소스 전체를 파싱하는 인덱서:

- `EndpointIndexer`
- `RouterFunctionIndexer`
- `GatewayRouteIndexer`
- `WsEndpointIndexer`
- `KafkaListenerIndexer`
- `ResponseDtoIndexer`
- `EnumConstantExtractor`

(`ConverterRegistryIndexer`는 `EndpointIndexer` 내부에서 이미 빌드된 `CtModel`을 재사용한다.)

### 두 가지 비효율

1. **중복 파싱**: 한 번의 `build()`에서 동일한 SUT 소스를 **7회 이상** Spoon으로 풀 파싱한다.
   모든 인덱서가 동일한 Launcher 환경설정(`noClasspath=true`, `commentEnabled=false`,
   `complianceLevel=17`)을 사용하므로, 단일 모델을 공유해도 결과가 달라지지 않는다.
2. **증분 부재**: 빌드 간 캐시가 전혀 없어, 소스가 한 글자도 안 바뀌어도 매번 전체를 재파싱한다.

500만 라인 기준 Spoon `buildModel()` 1회만으로도 수십 초~수 분과 수 GB 메모리가 든다.
이를 매 빌드마다 7회 이상 반복하는 것이 현재 구조다.

---

## 2. 목표와 비목표

### 목표

- **G1.** 한 번의 `build()`에서 Spoon 모델 빌드를 7회 이상 → **1회**로 줄인다(모델 공유).
- **G2.** 빌드 간 정적 인덱싱 결과를 캐시하여, 변경된 소스 파일만 재파싱한다(파일 단위 증분).
- **G3.** 소스 무변경 시 정적 인덱싱의 Spoon 빌드를 **0회**로 만든다(캐시 전체 복원).
- **G4.** 기존 동작과의 **결과 동등성**을 보장한다(증분/공유 결과 == 풀 리빌드 결과, 후술 한계 제외).

### 비목표

- 런타임 **탐색**(SUT 기동·호출·커버리지 수집) 증분화 — 이미 `IncrementalBuildPlanner`가 담당.
- Spoon 모델 자체의 직렬화/부분 컴파일 같은 Spoon 내부 메커니즘 개조.
- `--changed-files`(git diff 주입) 인터페이스 변경 — 그대로 둔다.

---

## 3. 결정 사항 (브레인스토밍 합의)

| # | 결정 | 근거 |
|---|---|---|
| D1 | **2단계 접근**: 모델 공유(Stage 1) 먼저, 그 위에 증분 캐시(Stage 2) | 모델 공유는 단순·저위험·즉효(7×↓)이며 증분의 검증된 토대 |
| D2 | 증분 변경 감지는 **빌더 자체 해시 매니페스트**(git 불요) | 자족적, 로컬/CI 동일 동작, 삭제/리네임 견고 |
| D3 | cross-file 정확성은 **속도 우선(A안)**: 변경 파일만 재파싱, 미변경 조각은 캐시 유지 | 병목은 모델 빌드. 드문 cross-file stale은 풀 리빌드로 정정 |
| D4 | 안전판: `--no-incremental` 플래그 + 스키마 버전 불일치 시 자동 풀 리빌드 | 정확성 복구 경로 보장 |

---

## 4. Stage 1 — 단일 Spoon 모델 공유

### 4.1 변경 개요

`build()`에서 SUT 소스로 `CtModel`을 **1회** 빌드하고, 위 7개 인덱서에 주입한다.

```
CtModel model = SharedSpoonModel.build(sutSrc);   // noClasspath, compliance 17, comments off
IndexResult index      = new EndpointIndexer().index(model, authConfig);
IndexResult functional = new RouterFunctionIndexer().index(model);
IndexResult gateway    = new GatewayRouteIndexer().index(model);
WsIndexResult ws       = new WsEndpointIndexer().index(model);
KafkaIndexResult kafka = new KafkaListenerIndexer().index(model);
List<Set<String>> dto  = new ResponseDtoIndexer().extract(model);
Map<...> enums         = new EnumConstantExtractor().extract(model);
```

### 4.2 인덱서 인터페이스

각 인덱서에 `CtModel`을 받는 오버로드를 추가하고, 기존 `Path` 진입점은 모델을 빌드해 위임한다.
(이미 `ConverterRegistryIndexer.index(CtModel)`이 동일 패턴의 선례다.)

```java
public IndexResult index(Path sutSrcDir, AuthConfig auth) {
    return index(SharedSpoonModel.build(sutSrcDir), auth);   // 하위호환
}
public IndexResult index(CtModel model, AuthConfig auth) { /* 본 로직 */ }
```

- 기존 `index(Path)` 시그니처와 동작은 보존된다(테스트·외부 호출 하위호환).
- `SharedSpoonModel`은 Launcher 환경설정을 한 곳에 모은 작은 헬퍼(중복 설정 제거).

### 4.3 위험과 완화

- **메모리**: 전체 모델 1개를 메모리에 유지. 현재도 인덱서들이 (순차적으로) 같은 크기 모델을
  반복 생성하므로 단일 모델 유지가 오히려 피크 메모리에 유리하다. 모델 참조는 인덱싱 종료 후 해제.
- **환경설정 차이**: 7개 인덱서 모두 동일 설정임을 확인함(§1). 향후 인덱서 추가 시 동일 설정을
  `SharedSpoonModel`에서 강제.

---

## 5. Stage 2 — 파일 단위 증분 캐시

### 5.1 캐시 레이아웃

```
<out>/index-cache/
  manifest.json          # 스키마버전 + 파일별 해시 + 조각 인덱스
  fragments/<key>.json   # 파일(또는 파티션) 단위로 직렬화된 인덱스 조각
```

`manifest.json`:

```json
{
  "schemaVersion": 1,
  "files": {
    "src/main/java/.../FooController.java": {
      "hash": "sha256:...",
      "fragmentKey": "io.app.web.FooController"
    }
  }
}
```

- `schemaVersion`: 빌더/인덱서 로직이 바뀌어 산출물 포맷이 달라지면 증가 → 불일치 시 자동 풀 리빌드.
- `hash`: 소스 파일 내용 해시(내용 기반; mtime 의존 회피로 체크아웃·CI 재현성 확보).
- `fragmentKey`: 그 파일이 기여한 인덱스 조각 식별자(보통 최상위 타입 FQCN).

### 5.2 인덱스 조각의 파일 귀속

정적 인덱싱 산출물은 대부분 **선언 파일 단위**로 귀속된다:

- `EndpointIndexer` → 핸들러 클래스 파일 단위 endpoints
- `EnumConstantExtractor` → enum 파일 단위 상수맵
- `ResponseDtoIndexer` → DTO 파일 단위 필드셋
- `WsEndpointIndexer` / `KafkaListenerIndexer` / `RouterFunctionIndexer` / `GatewayRouteIndexer`
  → 각 선언 파일 단위

조각 = "한 소스 파일에서 유래한 모든 인덱스 산출물의 묶음". 빌드 결과(`IndexResult` 등)는
모든 조각의 병합으로 재구성된다.

### 5.3 빌드 흐름

```
1. sutSrc 스캔 → 현재 .java/.xml 파일 집합과 각 해시 계산
2. 이전 manifest 로드 (없으면 풀 리빌드)
3. schemaVersion 불일치 → 풀 리빌드
4. diff 산출:
     added    = 현재에만 있는 파일
     modified = 해시가 달라진 파일
     deleted  = 이전에만 있는 파일
5. (added ∪ modified) == ∅ 이고 deleted == ∅:
     → 모든 조각을 캐시에서 로드 → IndexResult 재구성 (Spoon 빌드 0회)
6. 그 외:
     a. (added ∪ modified) 파일만으로 Spoon 모델 빌드 (부분 모델)
     b. 그 모델로 변경 파일의 조각만 재계산
     c. deleted 파일의 조각 제거
     d. 미변경 파일의 조각은 캐시에서 그대로 로드
     e. 전체 조각 병합 → IndexResult, manifest 갱신·저장
```

### 5.4 cross-file 정확성 한계 (명시)

A안(속도 우선)에서, 변경 파일만 부분 모델로 재파싱하므로 인덱서의 cross-file 타입 참조
(예: `EndpointIndexer.isEntityType` / `resolveRefTable`가 `model.getAllTypes()`로 타 파일의
`@Entity`/`@Table`을 찾는 로직, `@Converter`/`@Formatter` 등록, DTO bodyShape 해석)는
**부분 모델 안에서 해석이 약해질 수 있다**.

- 구체적 stale 시나리오: 파일 B가 파일 A의 타입을 참조하는데, **A만 바뀌고 B는 안 바뀐**
  경우, B의 캐시 조각이 옛 해석을 유지할 수 있다.
- 이 경우 결과는 "틀림"이 아니라 "이전 빌드 기준으로 보수적"이다. 빈도가 낮다.
- **정정 경로**: `--no-incremental`(풀 리빌드) 또는 캐시 삭제. 산출물 동등성 테스트(§7)가
  풀 리빌드와 증분의 차이를 회귀로 감시한다.

> 향후 개선(비목표): 파일 간 타입 의존 맵을 캐시해 "A 변경 시 A를 참조하는 B도 재계산"하는
> B안으로 확장 가능. 본 설계 범위 밖.

### 5.5 보수적 안전 규칙

- 리소스/XML 등 **파일→조각 귀속이 모호한 변경**은 해당 인덱서 전체를 재계산(보수적).
  (기존 `GraphPartitioner.dirtyPartitions`의 "매핑 불가 → 전체 더티" 정신 계승.)
- manifest 손상·역직렬화 실패 → 경고 후 풀 리빌드.

---

## 6. CLI / 설정

- `--no-incremental` (또는 `--reindex`): 캐시 무시하고 풀 리빌드 후 캐시 재작성.
- 캐시 위치: `<out>/index-cache/` (기존 `<out>/work/`와 형제). 기본 활성.
- 기존 `--incremental-base` / `--changed-files`(탐색 증분)와 **독립**. 동시 사용 가능.

---

## 7. 테스트 (E2E / 수용)

E2E는 실제 SUT 샘플에 대해 `build()`를 돌려 산출 `graph.json`을 비교하는 out-of-process 수준.

- **AT-1 (Stage 1 동등성)**: 동일 SUT에 대해 공유-모델 빌드의 `graph.json`이
  기존(개별-모델) 빌드 결과와 **동일**(골든 비교).
- **AT-2 (Stage 1 단일 빌드)**: 한 번의 `build()`에서 Spoon `buildModel` 호출이 1회임을
  계측(카운터/스파이)으로 확인.
- **AT-3 (무변경 증분)**: 동일 SUT 연속 2회 빌드 시, 2회차의 정적 인덱싱 Spoon 빌드 0회 +
  `graph.json` 1회차와 동일.
- **AT-4 (단일 파일 수정)**: 핸들러 1개 파일만 수정 후 재빌드 시, 그 파일 조각만 갱신되고
  나머지 조각은 캐시 재사용(부분 모델 입력이 변경 파일만 포함됨을 확인) + 최종 그래프 정확.
- **AT-5 (파일 삭제)**: 핸들러 파일 삭제 후 재빌드 시, 해당 엔드포인트 조각이 그래프에서 제거.
- **AT-6 (풀 리빌드 동등성)**: `--no-incremental` 결과 == 캐시 없는 초기 빌드 결과.
- **AT-7 (스키마 버전 무효화)**: manifest `schemaVersion`을 낮춰두면 자동 풀 리빌드.

단위 TDD(내부 루프): manifest diff 로직(added/modified/deleted), 조각 병합/제거,
`SharedSpoonModel` 설정 일치, 조각 직렬화 라운드트립.

### 완료 정의

- 위 AT-1~AT-7 전부 green.
- 기존 빌더 회귀 테스트(단위+통합+기존 E2E) green.
- 문서(README/도구 사용법) 갱신.

---

## 8. 구현 순서(개요, 상세는 plan에서)

1. Stage 1: `SharedSpoonModel` 헬퍼 + 7개 인덱서 `index(CtModel)` 오버로드 + `build()` 배선.
   → AT-1, AT-2 green.
2. Stage 2-a: 조각 모델 + 직렬화 + manifest diff. → 단위 테스트 green.
3. Stage 2-b: `build()`에 캐시 로드/병합/저장 배선 + `--no-incremental`. → AT-3~AT-7 green.

---

## 9. 미해결/리스크

- **R1.** Spoon noClasspath 부분 모델에서 변경 파일의 cross-file 해석이 어디까지 유지되는지는
  실제 샘플로 측정 필요(§5.4 한계의 실제 빈도). 측정 결과가 나쁘면 B안(의존맵) 승격 검토.
- **R2.** 500만 라인 규모의 실제 절감치는 벤치마크로 확인(목표: 무변경 재빌드 정적 인덱싱 시간
  90%+ 감소). 측정 환경/샘플 확보 필요.
- **R3.** 조각 직렬화 포맷(기존 `Json`/`JsonFileGraphStore` 재사용 여부)은 plan에서 확정.
