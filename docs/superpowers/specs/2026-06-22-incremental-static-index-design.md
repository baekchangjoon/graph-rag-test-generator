# 정적 인덱싱 증분화 설계 (graph-rag-builder)

- 작성일: 2026-06-22
- 대상: `graph-rag-builder` 도구 1 (`BuilderCli.build()`)의 **정적 인덱싱** 단계
- 배경: 대규모 SUT(약 500만 라인)에서 매 빌드마다 전체 소스를 재파싱하는 비용이 부담스럽다.
- 관련: 기존 증분 **탐색**(`--incremental-base` / `--changed-files`, `IncrementalBuildPlanner`)은
  런타임 탐색 단계 전용이며, 본 설계의 정적 인덱싱 캐시와 독립적이다.
- 개정: 3-벤더 design-doc 리뷰(Claude ×2 + Cursor) 반영 — 부분모델 cross-file 결함으로
  설계 기본 방향을 "변경파일만 부분 파싱(A안)"에서 "전체모델 1회 + whole-result 캐시(C안)"로 변경.

---

## 1. 문제 정의

`BuilderCli.build()`(graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java:162)는
호출 시마다 정적 인덱싱 산출물을 만들기 위해 여러 인덱서를 **각각 독립적으로** 실행한다.
대부분의 인덱서는 자체적으로 `new Launcher().addInputResource(...).buildModel()`을 수행한다.

### 1.1 정적 인덱싱 블록의 실제 구성 (코드 확인)

"정적 인덱싱 블록" = `build()` 진입부터 `explore()`(탐색) 호출 직전까지. 다음을 생성한다:

| 산출물 | 인덱서 | 입력 | Spoon? | build() 위치 |
|---|---|---|---|---|
| `IndexResult`(HTTP endpoints) | `EndpointIndexer` (+내부 `ConverterRegistryIndexer`가 `CtModel` 재사용) | `sutSrc` | O | L164 |
| `IndexResult` 병합 | `RouterFunctionIndexer` | `sutSrc` | O | L165 |
| `IndexResult` 병합 | `GatewayRouteIndexer` | `sutSrc` | O | L170 |
| `WsIndexResult` | `WsEndpointIndexer` | `sutSrc` | O | L175 |
| `KafkaIndexResult` | `KafkaListenerIndexer` | `sutSrc` | O | L177 |
| `List<MapperStatement>` | `MapperXmlIndexer` | **`sutResources`** | **X (XML DOM)** | L179 |
| `List<Set<String>>` responseDtoFieldSets | `ResponseDtoIndexer` | `sutSrc` | O | L182 |
| `Map` enumConstants | `EnumConstantExtractor` | `sutSrc` | O | **L230 (탐색 plan 계산 후)** |

즉 Spoon 풀 파싱은 **6회**(Endpoint/Router/Gateway/Ws/Kafka/ResponseDto) + `EnumConstantExtractor` 1회
= **7회**, 위치는 균일하지 않다. `MapperXmlIndexer`는 Spoon이 아니라 XML 파서이며 `sutResources`를 본다.

모든 Spoon 인덱서는 동일한 Launcher 환경설정(`noClasspath=true`, `commentEnabled=false`,
`complianceLevel=17`)을 사용하므로 단일 모델 공유 시 결과가 달라지지 않는다.

### 1.2 두 가지 비효율

1. **중복 파싱**: 한 번의 `build()`에서 동일한 SUT 소스를 7회 풀 파싱한다.
2. **증분 부재**: 빌드 간 캐시가 없어, 소스가 한 글자도 안 바뀌어도 매번 전체를 재파싱한다.

500만 라인 기준 Spoon `buildModel()` 1회만으로도 수십 초~수 분과 수 GB 메모리가 든다.

---

## 2. 목표와 비목표

### 목표

- **G1.** 한 번의 `build()`의 정적 인덱싱 블록에서 Spoon 모델 빌드를 7회 → **1회**로 줄인다(모델 공유).
- **G2.** 빌드 간 정적 인덱싱 결과를 캐시하여, 소스가 **무변경이면 Spoon 빌드 0회**로 전체 산출물을
  복원한다(캐시 복원).
- **G3.** 소스에 변경이 있으면 전체 모델 1회 빌드 + 전체 인덱서 재실행으로 산출물을 정확히 갱신하고
  캐시를 재작성한다(증분 == 풀 리빌드).
- **G4.** 기본 경로(C안)에서 캐시 사용 결과 == 풀 리빌드 결과의 **완전 동등성**을 보장한다.

### 비목표

- 런타임 **탐색** 단계의 Spoon 파싱: `ConstraintExtractor`(메서드별 4회), 엔드포인트마다 호출되는
  `ValidationConstraintExtractor`·`LiteralCandidateExtractor`(BuilderCli.java:487~595)는 본 설계
  범위 밖이다. 이들은 탐색 루프에 묶여 있어 별도 과제로 다룬다(향후 확장 여지로만 기록).
- 런타임 **탐색** 증분화(이미 `IncrementalBuildPlanner` 담당).
- Spoon 모델의 직렬화/부분 컴파일 같은 Spoon 내부 메커니즘 개조.
- `--changed-files`(git diff 주입) 인터페이스 변경.

---

## 3. 결정 사항

| # | 결정 | 근거 |
|---|---|---|
| D1 | **2단계 접근**: 모델 공유(Stage 1) 먼저, 그 위에 증분 캐시(Stage 2) | 모델 공유는 단순·저위험·즉효(7×↓)이며 증분의 검증된 토대 |
| D2 | 증분 변경 감지는 **빌더 자체 해시 매니페스트**(git 불요). 스캔 루트는 `sutSrc`(.java) + `sutResources`(.xml) 두 곳 | 자족적, 로컬/CI 동일 동작, 삭제/리네임 견고. 실제 입력이 두 루트로 분리됨 |
| D3 | **기본 경로 = C안(전체모델 1회 빌드 + whole-result 캐시)**. 무변경 시 캐시 복원으로 Spoon 0회, 변경이 1건이라도 있으면 전체모델 1회 빌드 + 전체 인덱서 재실행 후 캐시 재작성 | 부분모델은 cross-file 타입 해석을 즉시 깨뜨림(§5.4). 전체모델이라 cross-file 항상 정확하며 G4 동등성 보장. 조각 단위 부분 갱신은 인덱서 7개 대수술이 필요하고 변경-빌드 순회생략 가치가 불확실(R2)하여 YAGNI로 제외 |
| D4 | **부분모델(B/A안)은 벤치마크 게이트 옵션**으로만. C안 측정 후 "변경 빌드도 줄여야" 하고 정확성 손실이 허용되면 도입 | 500만 라인에서 변경 빌드 비용을 더 줄이는 유일한 길이나 정확성 트레이드오프가 있음. 측정 없이 채택하지 않음(§9 R1/R2) |
| D5 | 안전판: `--no-incremental` 플래그 + 스키마 버전 불일치 시 자동 풀 리빌드 + manifest 손상 시 풀 리빌드 | 정확성 복구 경로 보장 |

> **방향 전환 주의(리뷰 반영):** 초기 브레인스토밍에서 secretary가 고른 "A안(변경파일만 부분
> 재파싱, 속도우선)"은 3-벤더 리뷰에서 인덱서의 cross-file 타입 해석을 즉시 silently-wrong으로
> 만든다는 점이 드러나 **기본 경로에서 철회**했다. 사용자 확인이 필요한 핵심 변경이다(§10).

---

## 4. Stage 1 — 단일 Spoon 모델 공유

### 4.1 변경 개요

정적 인덱싱 블록에서 SUT 소스로 `CtModel`을 **1회** 빌드하고, Spoon 인덱서 7개에 주입한다.
`MapperXmlIndexer`는 Spoon과 무관하므로 별도 유지(`sutResources` XML).

```
CtModel model = SharedSpoonModel.build(sutSrc);   // noClasspath, compliance 17, comments off
IndexResult index      = new EndpointIndexer().index(model, authConfig);   // 내부 ConverterRegistryIndexer도 model 재사용
index = index.merge(new RouterFunctionIndexer().index(model));
index = index.merge(new GatewayRouteIndexer().index(model));
WsIndexResult ws       = new WsEndpointIndexer().index(model);
KafkaIndexResult kafka = new KafkaListenerIndexer().index(model);
List<Set<String>> dto  = new ResponseDtoIndexer().extract(model);
Map<...> enums         = new EnumConstantExtractor().extract(model);   // build()에서 이 시점으로 앞당김(L230→정적 블록)
List<MapperStatement> mappers = new MapperXmlIndexer().index(sutResources);  // XML, 모델 무관
```

- `EnumConstantExtractor` 호출을 정적 인덱싱 블록으로 앞당겨 동일 `model`을 공유한다(현재 L230 →
  탐색 plan 계산 전). 산출물(enumConstants)은 plan 계산에 영향을 주지 않으므로 위치 이동이 안전하다.

### 4.2 인덱서 인터페이스

각 Spoon 인덱서에 `CtModel`을 받는 오버로드를 추가하고, 기존 `Path` 진입점은 모델을 빌드해 위임한다.
(이미 `ConverterRegistryIndexer.index(CtModel)`이 동일 패턴의 선례.)

```java
public IndexResult index(Path sutSrcDir, AuthConfig auth) {
    return index(SharedSpoonModel.build(sutSrcDir), auth);   // 하위호환
}
public IndexResult index(CtModel model, AuthConfig auth) { /* 본 로직 */ }
```

- 기존 `index(Path)`/`extract(Path)` 시그니처·동작 보존(테스트·외부 호출 하위호환).
- `SharedSpoonModel`은 Launcher 환경설정을 한 곳에 모은 헬퍼이며, **Spoon 빌드 호출 계측**을 위해
  `static AtomicInteger buildCount`(테스트에서 reset/read)를 노출한다(§7 AT-2 계측 수단).

### 4.3 위험과 완화

- **메모리**: 전체 모델 1개를 메모리에 유지. 현재도 인덱서들이 같은 크기 모델을 순차 반복 생성하므로
  단일 모델 유지가 피크 메모리에 유리. 인덱싱 종료 후 참조 해제.
- **환경설정 일관성**: 7개 인덱서 모두 동일 설정 확인(§1.1). 향후 인덱서는 `SharedSpoonModel` 경유 강제.

---

## 5. Stage 2 — 증분 캐시 (기본: C안, whole-result)

조각 단위 부분 갱신은 인덱서 7개를 "파일별 그룹 반환"으로 대수술해야 하고, 변경-빌드 순회생략의
가치가 불확실(R2)하다. 사용자 핵심 목적(무변경 반복 빌드의 부담 제거)은 **whole-result 캐시**로
100% 달성되므로, YAGNI로 조각 단위를 제외하고 다음 단순 모델을 채택한다.

### 5.1 캐시 레이아웃

```
<out>/index-cache/
  manifest.json          # 스키마버전 + (sutSrc .java + sutResources .xml) 파일별 해시
  static-index.json      # 직렬화된 정적 인덱싱 산출물 전체 (단일 파일)
```

`manifest.json`:

```json
{
  "schemaVersion": 1,
  "files": {
    "src/main/java/.../FooController.java": { "root": "sutSrc",       "hash": "sha256:..." },
    "mapper/FooMapper.xml":                  { "root": "sutResources", "hash": "sha256:..." }
  }
}
```

`static-index.json`: 정적 인덱싱 산출물 묶음 = 병합된 `IndexResult`(HTTP/Router/Gateway), `WsIndexResult`,
`KafkaIndexResult`, `List<MapperStatement>`, `List<Set<String>>` responseDtoFieldSets, `Map` enumConstants.

- **`schemaVersion`**: 정적 인덱싱 산출물 포맷/인덱서 로직 변경 시 올리는 상수. 위치/정책:
  전용 `IndexCache` 클래스의 `static final int SCHEMA_VERSION` 상수. 관련 record
  (`IndexResult`/`WsIndexResult`/`KafkaIndexResult`/`Endpoint`/`FormFieldBinding`/`BodyShape`/
  `MapperStatement` 등)나 `static-index.json` 포맷이 바뀌는 PR에서 **수동 bump**(코드 리뷰
  체크리스트 항목). 불일치 시 **방향 무관**(상위/하위 모두) 풀 리빌드.
- **`hash`**: 소스 파일 내용 SHA-256(내용 기반 — mtime 의존 회피로 체크아웃·CI 재현성 확보).
- **`root`**: `sutSrc`(.java 스캔) 또는 `sutResources`(.xml 스캔) 구분.

### 5.2 빌드 흐름 (C안 / whole-result)

```
1. sutSrc(.java) + sutResources(.xml) 스캔 → 루트별 상대경로·내용 해시 계산
2. 이전 manifest 로드 (없거나 schemaVersion 불일치 또는 손상 → 풀 리빌드 경로로)
3. 현재 해시맵 == 이전 manifest 해시맵 (모든 파일 동일, 추가/수정/삭제 0건)?
     YES → static-index.json 역직렬화 → 산출물 복원 (Spoon 빌드 0회)            [G2]
     NO (또는 --no-incremental / 캐시 없음/손상) → 풀 리빌드:
       a. 전체 SUT로 Spoon 모델 1회 빌드 (SharedSpoonModel; Stage 1)
       b. 7개 Spoon 인덱서가 모델 공유로 전체 산출물 생성 + MapperXmlIndexer(XML) 실행
       c. 산출물을 static-index.json 으로, 현재 해시맵을 manifest.json 으로
          원자적 저장(temp 후 atomic rename)
```

이점: 변경 시에도 모델이 전체이므로 `isEntityType`/`resolveRefTable`/`fieldsOf`/`configLiteral`
등 cross-file 해석이 항상 정확 → **G4 완전 동등성**(증분 == 풀 리빌드). 무변경 시 Spoon 0회.

한계: 변경이 1건이라도 있으면 Spoon 1회 풀 빌드 + 전체 인덱서 재실행이다. 즉 변경 빌드 비용은
Stage 1 수준(7→1회)까지만 줄고 그 이하로는 못 준다. 매 PR마다 변경이 있는 CI에서 그 비용까지
줄이려면 §5.4 부분모델 옵션이 필요(트레이드오프 있음).

### 5.3 동시성·복구

같은 `<out>` 디렉터리에 동시 빌드가 일어날 수 있다. manifest/static-index 쓰기는 **temp 파일 작성
후 atomic rename**으로 수행한다. 단일-writer 권장. manifest/static-index 역직렬화 실패(손상)·
schemaVersion 불일치 시 경고 후 풀 리빌드로 복구한다.

### 5.4 부분모델 옵션 (B/A안) — 벤치마크 게이트, 기본 비활성

변경 빌드의 Spoon 비용까지 줄이려면 변경 파일만(또는 변경 파일 + 의존 closure) 부분 모델을 빌드해야
한다. 그러나 인덱서는 cross-file 타입 해석을 한다:

- `EndpointIndexer.classifyFormBindings` → `isEntityType`/`resolveRefTable`/`isNestedPojo`가
  `model.getAllTypes()`를 순회(EndpointIndexer.java:263-334, BodyShapeExtractor).
- 부분 모델에 **변경 파일이 참조하는 미변경 타입이 없으면** `isEntityType`가 false를 반환 →
  `FormFieldBinding.kind`가 REFERENCE가 아닌 SCALAR로 **오분류**(silently wrong) → 런타임 토큰
  trial 생략 → 생성 테스트가 달라진다. 이는 "보수적"이 아니라 **잘못된 결과**다.
- `ResponseDtoIndexer.fieldsOf`, `WsEndpointIndexer.configLiteral`도 동일 위험.

옵션 단계:

- **A안(순수 부분모델)**: 변경 파일만. 최대 절감, cross-file 깨짐 → **비권장**.
- **B안(부분모델 + 의존 closure)**: 변경 파일 + 그 파일이 import/참조하는 타입의 파일까지 부분
  모델에 포함. cross-file 상당 보존, transitive closure 계산 비용·복잡성. 채택 시 WsEndpointIndexer의
  전역 `configLiteral` 설정 파일이 변경되면 전체 WS 산출물을 무효화하는 보수 규칙이 필수.

도입 조건(§9 R1/R2): C안 벤치마크에서 변경 빌드 비용이 여전히 과도하고, B안의 정확성이 수용
가능(샘플 SUT에서 REFERENCE/DTO 분류 동등성 검증)할 때만. 측정 없이 채택하지 않는다.

---

## 6. CLI / 설정

- `--no-incremental` (또는 `--reindex`): 캐시 무시하고 풀 리빌드 후 캐시 재작성.
  → `BuildConfig`에 `boolean noIncremental` 필드 추가, `BuilderCli.main()` 옵션 파싱에 배선.
- 캐시 위치: `<out>/index-cache/`(기존 `<out>/work/`와 형제). 기본 활성.
- 기존 `--incremental-base` / `--changed-files`(탐색 증분)와 **독립**, 동시 사용 가능.

---

## 7. 테스트 (E2E / 수용)

E2E는 실제 SUT 샘플에 대해 `build()`를 돌려 산출 `graph.json`을 비교하는 out-of-process 수준.
기존 `BuilderIntegrationTest`(sut.jar/sut.src 시스템 프로퍼티·Docker 요구) 패턴을 따르되, 정적
인덱싱 동등성은 Docker 없이도 검증 가능한 경량 골든 비교로 분리한다.

**계측 범위 정의:** AT-2/AT-3의 "Spoon 빌드 횟수"는 **정적 인덱싱 블록**(build() 진입~explore 호출
직전)에 한정한다. 탐색 단계의 ConstraintExtractor/Validation/Literal 파싱은 비목표(§2)이므로 집계
제외. 계측 수단 = `SharedSpoonModel.buildCount`(§4.2).

- **AT-1 (Stage 1 동등성)**: 동일 SUT의 공유-모델 빌드 `graph.json` == 기존(개별-모델) 결과(골든).
- **AT-2 (Stage 1 단일 빌드)**: 한 번의 정적 인덱싱 블록에서 `SharedSpoonModel.buildCount == 1`.
- **AT-3 (무변경 증분)**: 동일 SUT 연속 2회 빌드 시, 2회차 정적 블록 `buildCount == 0` +
  `graph.json` 1회차와 동일.
- **AT-4 (단일 파일 수정 후 재빌드)**: 핸들러/DTO 파일 1개 수정 후 재빌드 시, 변경이 감지되어
  풀 리빌드가 일어나고(`buildCount == 1`) 최종 `graph.json`이 풀 리빌드 결과와 동일.
- **AT-5 (파일 삭제)**: 핸들러 파일 삭제 후 재빌드 시 해당 엔드포인트가 `graph.json`에서 제거되고
  풀 리빌드 결과와 동일.
- **AT-6 (증분 == 풀 리빌드 동등성)**: 임의 변경 시퀀스 후 캐시 사용 결과 `graph.json` ==
  `--no-incremental` 결과(C안 G4 핵심 회귀).
- **AT-7 (스키마 버전 무효화)**: manifest `schemaVersion`을 낮춰두면 자동 풀 리빌드.
- **AT-8 (XML 변경)**: mapper XML 1개 수정 후 재빌드 시 mappers 산출물 갱신 + 풀 리빌드와 동일.

**샘플 SUT 픽스처:** AT-1/AT-4/AT-6용 SUT는 cross-file 케이스를 포함해야 한다 —
(1) `@Entity` 참조 FORM 커맨드 필드(classifyFormBindings 경로), (2) 중첩 BodyShape DTO,
(3) Converter/Formatter 등록, (4) RestTemplate DTO 호출, (5) MyBatis mapper XML. 기존 sample-src에
없으면 픽스처 추가.

단위 TDD(내부 루프): manifest diff(무변경/변경 판정, 루트별 해시맵 비교), static-index 직렬화
라운드트립, `SharedSpoonModel` 설정·카운터, atomic write.

### 완료 정의

- AT-1~AT-8 전부 green + 기존 빌더 회귀(단위+통합+기존 E2E) green + 영향 문서 갱신.

---

## 8. 구현 순서(개요, 상세는 plan에서)

1. **Stage 1**: `SharedSpoonModel`(설정+buildCount) + 7개 Spoon 인덱서 `index(CtModel)` 오버로드 +
   `build()` 배선(EnumConstantExtractor 위치 이동 포함). → AT-1, AT-2 green.
2. **Stage 2-a**: `IndexCache`(manifest 모델 + static-index 직렬화: io.graphrag.model.Json 재사용
   가능성 확인, 불가 시 전용 DTO) + 해시 스캔(두 루트) + 무변경/변경 판정 + atomic write +
   schemaVersion/손상 시 풀 리빌드. → 단위 테스트 green.
3. **Stage 2-b**: `build()`에 캐시 로드(무변경 복원)/풀 리빌드/저장 배선 + `--no-incremental`
   (`BuildConfig`/CLI). → AT-3~AT-8 green.
4. **(옵션, 측정 후)** 부분모델 B안: R1/R2 벤치마크 결과가 도입을 정당화할 때만 별도 plan.

---

## 9. 미해결 / 리스크

- **R1.** C안 변경 빌드는 Spoon 1회 풀 빌드를 유지한다. 매 PR 변경이 있는 CI에서 이게 여전히 과도하면
  부분모델 B안(§5.4)이 필요. B안의 cross-file 정확성(SCALAR 오분류 빈도)을 실제 샘플로 측정해야 도입
  가부 판단 가능. **측정 전 채택 금지.**
- **R2.** 500만 라인에서 실제 절감치 벤치마크 필요: (a) Stage 1으로 7→1회 절감폭, (b) 무변경 재빌드
  0회의 정적 인덱싱 시간 절감(목표 90%+). C안의 핵심 가치는 "Stage 1(7→1회) + 무변경 캐시(0회)"다.
- **R3.** static-index 직렬화: `IndexResult`/`WsIndexResult`/`KafkaIndexResult`/`MapperStatement` 등의
  Jackson 직렬화 가능성을 Stage 2-a 착수 전 확인. `JsonFileGraphStore`는 GraphAsset 전용이라 직접
  재사용은 어려울 수 있음 → static-index 전용 DTO/직렬화 결정.
- **R4.** Spoon 단일 파일 부분 빌드의 미해석 TypeRef 빈도·Launcher 초기화 고정비용은 B안 검토 시 측정.

---

## 10. 사용자 확인 필요 사항

> **사용자 결정(2026-06-22): C안 진행.** plan 범위 = Stage 1 + Stage 2 캐시(C안). B안(부분모델)은
> R1/R2 벤치마크 후 별도 과제로만 검토하며 본 plan 범위에서 제외한다.

1. **방향 전환 승인(D3)**: 리뷰 결과 부분모델(A안)이 cross-file 정확성을 깨므로, 기본을 C안(전체모델
   1회 + whole-result 캐시)으로 변경했다. 이 경우 **무변경 재빌드는 Spoon 0회로 크게 빨라지지만, 변경이 있는
   빌드는 여전히 Spoon 1회 풀 빌드**(= Stage 1 수준)다. "변경 빌드까지 더 줄이는 것"이 핵심 요구라면
   부분모델 B안을 (정확성 트레이드오프와 함께) 별도 단계로 추진해야 한다 — 어느 쪽을 원하는지 확인.
2. **CI 사용 패턴**: 주 사용처가 (a) 동일 커밋 반복 빌드/로컬 재실행(무변경 잦음 → C안으로 충분)인지,
   (b) 매 PR 변경 빌드(변경 잦음 → B안 필요)인지에 따라 우선순위가 갈린다.
