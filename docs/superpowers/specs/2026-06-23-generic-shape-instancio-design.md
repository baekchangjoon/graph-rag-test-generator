# 임의 형상 입력 생성 — generic 재귀 빌더 + Instancio 폴백 — 설계

- 일자: 2026-06-23
- 브랜치: feat-generic-shape-instancio
- 배경: [`2026-06-22-array-nested-mutation-joinguard-design.md`](2026-06-22-array-nested-mutation-joinguard-design.md)
  (배열/중첩/조인가드, 머지됨). 그 작업의 교훈 — **새 바디 형상마다 직접 케이스를 추가하다 조용히
  실패/스킵**(List<DTO> `instanceof ObjectNode` 가드 silent no-op, form↔JSON 공유 컴포넌트 충돌). 본 작업은
  그 *반복 패턴* 을 구조적으로 제거한다.
- 결정(인박스/사용자): **C 하이브리드 + Option 1 + Instancio 1차 포함**.
  - C: generic-Spoon 재귀 빌더가 기본, Instancio는 Spoon이 못 푸는 타입의 폴백.
  - Option 1: `BodyShape`(dot-path + javaType)를 정식 경로·타입 모델로 *유지·완성*; concolic 필드명→dot-path
    lifter로 중첩 값 매핑 갭을 닫는다.
- 스코프 밖(후속): LLM 값-오라클, OpenAPI 입력 소스.

## 문제

현재 입력 생성은 **형상마다 직접 모델링·조립**한다. `BodyShapeExtractor.extractFromTypeFlattened`(깊이
상한 2, DTO 재귀만), `SampleInputSynthesizer`(필드별 조립), explorer의 컨테이너 분기. 새 형상(예:
`Map<K,V>` 바디, `List<scalar>` 원소 변이, 깊이 3+ 중첩, 중첩 컬렉션 `List<List<>>`, 다형 `oneOf`,
**Spoon이 못 푸는 외부/shadow 타입**)이 오면 — (A) shape 미해결 → 엔드포인트 스킵, 또는 (B) 다운스트림
단계가 조용히 no-op. 둘 다 *조용히* 실패한다.

또한 concolic `InputCandidates`는 **필드 leaf명**(`Map<String,Set<Long>>`, 예 `min`)으로 키잉돼 중첩
경로(`range.min`)와 안 맞아, 중첩 필드의 값/조인가드가 닿지 않는다(직전 작업에서 중첩 조인가드를 범위
밖으로 뺀 이유).

## 범위

- **포함**:
  1. **generic 재귀 구조 모델·happy 빌더** — 임의 형상(중첩 DTO, `List/Set<DTO|scalar>`, 중첩 컬렉션,
     `Map`, 다형 best-effort)을 *재귀 한 경로* 로 처리. dot-path `BodyShape` + 일치하는 nested JSON happy
     바디 생성. per-shape 코드 없음.
  2. **generic 변이 순회** — 변이 적용/경로 발견이 컨테이너 타입에 short-circuit하지 않음(중첩 배열·
     배열-of-중첩 포함).
  3. **concolic 필드명→dot-path lifter** — `InputCandidates`의 leaf명을 `BodyShape` dot-path로 승격
     (모호 시 매칭 전 경로에 적용).
  4. **Instancio 폴백** — Spoon이 못 푸는 타입은 **sut-jar에서 런타임 `Class<?>` 로드 → Instancio(seed
     결정적) populate → Jackson 직렬화** 로 happy 바디·dot-path 도출.
  5. **loud-failure 백스톱** — 모델 불가 형상을 `ExplorationReport`에 `unsupported-shape`로 *기록*(조용히
     스킵 금지) + 회귀 불변식(엔드포인트별 explored-path 수 비축소).
- **제외(YAGNI)**: LLM/OpenAPI 입력 소스, 다형 완전 해결(서브타입 선택은 best-effort+기록), 컬렉션 원소
  *별* 서로 다른 변이(element[0] 대표 유지).

## 설계

### 변경 1 — generic 재귀 구조 모델·happy 빌더 (Spoon 경로)

`BodyShapeExtractor.extractFromTypeFlattened`와 happy 합성을 *완전 재귀* 로 일반화한다. 한 재귀 함수가
타입을 분류해 처리:

- **scalar/enum/시간** → 리프(dot-path BodyField + javaType). happy 값은 기존 `SampleInputSynthesizer`
  스칼라 로직 재사용.
- **DTO(record/class)** → 컴포넌트별 재귀(`parent.child` 누적). 경로별 cycle guard(스택-로컬 Set), 깊이
  상한은 **원칙화**: `MAX_DEPTH`(기본 5) 초과 또는 cycle → 그 경로를 리프로 *종료하고 `unsupported-shape`
  사유 없이 정상 종료*(폭주 방지, silent 아님 — 종료는 의도된 동작).
- **컬렉션(`List/Set/Collection/Iterable`, 배열)** → 원소 타입 재귀로 BodyShape(collection=true) + happy는
  1-element 배열. 원소가 DTO면 중첩 dot-path, scalar면 스칼라 리프.
- **`Map<K,V>`** → happy는 1-entry 오브젝트(`{"<sampleKey>": <sampleV>}`); 변이는 entry 값에 적용. (키
  타입은 String 가정, 비-String이면 `unsupported-shape` 기록.)
- **미해결(shadow/외부/opaque generic)** → **변경 4(Instancio 폴백)** 로 위임; 폴백도 실패면 `unsupported`.

happy JSON 중첩은 직전 작업의 `JsonPaths.nestDottedKeys`(runner, non-form JSON object) 재사용 — dot-path
리프를 중첩 객체로. (form 경로는 불변.)

### 변경 2 — generic 변이 순회

직전 작업의 `applyToBody`(배열 element[0])·`JsonPaths`(dot-path put/remove)를 확장:
- 변이 경로 = `BodyShape`의 dot-path 필드(변경 1이 임의 형상까지 채움). 별도 트리-walk 불필요(Option 1).
- `applyToBody`가 **중첩 배열·배열-of-중첩** 도 element[0] 대표로 재귀 적용. 비-ObjectNode 원소는 가드.
- 어떤 단계도 컨테이너 타입에 `return`(silent no-op) 하지 않음 — 미처리 분기는 **기록**(변경 5).

### 변경 3 — concolic 필드명→dot-path lifter

`InputMutator.forTarget`(또는 EndpointTarget 조립부)에서 concolic `InputCandidates`(leaf명 키)를
`mutableFields`(dot-path)로 승격하는 순수 매퍼:
- leaf명 == dot-path의 마지막 세그먼트면 그 경로로 매핑. 다중 매칭(같은 leaf명이 여러 부모) → **전 경로에
  적용**(보수적, 무해한 추가 변이). 매칭 0 → 최상위 평면 필드로 폴백(기존 동작).
- joinGuards의 nested 필드도 같은 lifter로 — 직전 작업에서 범위 밖이던 중첩 조인가드가 닫힌다.
- 결정적(정렬). 기존 평면 케이스 회귀 0(leaf==최상위명이면 자기 자신).

### 변경 4 — Instancio 폴백 (sut-jar 리플렉션)

Spoon이 바디 타입을 못 풀 때만 발동. 새 컴포넌트 `ReflectiveBodyInstantiator`:

- **클래스로딩**: `--sut-jar`이 **Spring Boot fat jar**(BOOT-INF/classes·lib)임을 확인 → fat jar를 임시
  디렉터리로 **추출**(BOOT-INF/classes + BOOT-INF/lib/*.jar) → 그 위로 **child `URLClassLoader`**(부모=
  플랫폼 로더, 빌더의 Jackson/Spoon 격리) 구성. (1회 빌드, 캐시.) exploded `build/classes`만으로는 전이
  의존이 빠지므로 fat jar 추출이 정공.
- **인스턴스화**: `loader.loadClass(fqn)` → **Instancio**(`Instancio.of(clazz).withSeed(<fqn 해시>).create()`,
  seed 결정적)로 populate. 컬렉션/중첩/제네릭/record 자동.
- **직렬화**: 빌더의 Jackson으로 객체 그래프 → JSON(getter/field 리플렉션, cross-loader OK). 그 JSON에서
  dot-path BodyShape 도출(트리 walk) + happy 바디로 사용.
- **값 덮기**: Instancio는 *유효 스켈레톤*(junk 값). 도메인 값(FK probe·enum·concolic)은 기존처럼 JSON에
  덮어씀(변경 1~3 경로 공용).
- **활성화**: 자동(Spoon 미해결 시) — 단 `--no-reflect-instantiate`로 비활성(그 경우 `unsupported` 기록).
- **결정성**: seed 고정. Instancio는 seed-재현 가능(LLM과 달리 불변식 호환).
- **리스크(아래 §리스크)**: fat-jar 추출 비용, classloader 격리, cross-loader Jackson(커스텀 직렬화/모듈),
  전이 의존 누락. 빌더 JVM 로딩이 부적합으로 판명되면 **분석 환경(Testcontainers, SUT가 이미 풀 classpath로
  로드됨) 내 인스턴스화 엔드포인트** 로 대체(A2) — 1차는 빌더-JVM 추출 로딩, A2는 폴백.

### 변경 5 — loud-failure 백스톱

- generic 빌더/변이/폴백이 *처리 못한* 바디 타입·형상을 `ExplorationReport`(기존 `drops` 채널)에
  `unsupported-shape`(타입 FQN + 사유)로 기록. **조용히 스킵 금지.**
- `BuilderCli`의 endpoint skip 분기도 이 기록을 거치게.
- 회귀 불변식: 빌더 산출 메타에 endpoint별 explored-path 수 노출 → e2e가 "알려진 컬렉션/중첩 엔드포인트는
  path > 1"을 단언(직전 List<DTO> 버그 같은 silent no-op이 *테스트로* 실패).

### 데이터 흐름

```
SUT 타입 ─Spoon 해결?─┬─yes→ generic 재귀 BodyShape(dot-path+javaType) ─┐
                      └─no → ReflectiveBodyInstantiator(Instancio)      │
                              (sut-jar 추출→URLClassLoader→populate→Jackson) → dot-path BodyShape ─┤
                                                                                                    ├→ mutableFields
concolic InputCandidates(leaf명) ─lifter→ dot-path ───────────────────────────────────────────────┘
        │
   generic happy 빌더 + nestDottedKeys → nested JSON happy
        │
   explorer/fuzzer: applyToBody(중첩/배열 generic) + JsonPaths 변이 ── 미처리? → report(unsupported)
        │
   실행+JaCoCo → 커버리지/SQL/trace → 결정적 JUnit
```

## E2E / 수용 기준 (요구사항명세에서 REQ-ID)

최고 가능 수준 = 기존 e2e(빌드→탐색→생성→docker→테스트 GREEN). 신규 fixture는 "현재 코드가 스킵/오처리할
새 형상":
- **AC-1(generic 중첩 깊이>2)**: 깊이 3+ 중첩 DTO 바디 → happy 중첩 JSON 2xx + 깊은 리프 변이가 분기 도달.
- **AC-2(Map 바디 or List<scalar> 원소)**: 현재 미지원 형상이 happy 합성 + ≥1 변이.
- **AC-3(Instancio 폴백)**: Spoon이 못 푸는 타입(shadow/외부)을 가진 바디 → Instancio로 happy 인스턴스화·
  탐색(빌더 단위/통합 레벨; e2e 가능 시 e2e).
- **AC-4(concolic 중첩 lifter)**: 중첩 숫자 필드의 concolic/조인가드 값이 그 경로에 적용(단위).
- **AC-5(loud-failure)**: 진짜 미지원 형상이 `unsupported-shape`로 기록되고 조용히 스킵되지 않음(단위/통합).
- **AC-6(회귀)**: 기존 전 모듈 + e2e GREEN, 평면/배열/중첩 기존 동작·커버리지 불변(superset).

## 리스크
- **fat-jar classloading**(최대 리스크): Spring Boot jar 추출·child loader·cross-loader Jackson·전이 의존.
  → 1회 추출 캐시 + 플랫폼-부모 격리 + 실패 시 `unsupported` 기록(폭주 금지) + 분석환경(A2) 대체 경로 명시.
- **신규 의존(Instancio)**: gradle dep 추가. 결정적(seed)이라 불변식 호환.
- **generic 재귀 폭주**: 깊이·cycle·컬렉션 1-element 상한으로 제한.
- **lifter 모호성**: 다중 매칭 시 전 경로 적용(추가 변이는 markTried/예산이 흡수).
- **공유 컴포넌트 회귀**(직전 교훈): form 경로·SampleInputSynthesizer 리터럴 계약 불변 — generic 빌더는
  JSON @RequestBody 경로 한정, form은 FormBodySynthesizer 유지. AC-6로 가드.
