# 의사결정: test-generator 합성 규칙

날짜: 2026-06-10 / 단계: 0.7 (Phase 1 개정: 1.5 — 아래 "Phase 1 개정" 절)

## 구조 (docs/04 방식 C 준수)

- 큰 골격: Mustache 템플릿 1개 (`templates/test-class.mustache`)
- 가변 슬롯: `FixtureComposer` 프로그램이 합성
  (치환 변수 / fixture INSERT / cleanup DELETE / body 포맷 / 응답 assertion)
- LLM 없음, 시간/Random 없음 → 동일 입력 = byte 동일 출력 (결정성 테스트로 보증)

## 합성 규칙 (Phase 0)

| 규칙 | 구현 |
|---|---|
| API_PARAM 치환 | body 필드 값이 PK/FK 컬럼의 API_PARAM 바인딩에 닿으면 `scope.testId() + "-<suffix>"`로 치환. suffix는 필드명에서 `Id` 제거 (`userId`→`user`) |
| LITERAL 보존 | 치환 대상이 아닌 body 값은 sampleInput 그대로 |
| 픽스처 합성 | 캡처된 SELECT가 조회한 테이블에 사전 INSERT. 키 컬럼=치환 변수, NOT NULL은 타입별 기본값 |
| Cleanup | SUT가 INSERT한 행 + 픽스처 행을 FK 깊이 역순(자식 먼저)으로 DELETE. 자기 스코프(`WHERE key=?`)만 |
| 응답 검증 | 값이 LITERAL 바인딩과 일치하는 필드 → `equalTo`, 그 외 → `notNullValue()`. DB 상태 검증 없음 (docs/06) |
| 병렬 안전 | Phase 0은 DB testId 격리 + mock 미사용 → `fully_parallel` 보고 |

## 알려진 한계

- 응답 assertion의 "LITERAL 값 일치" 판정은 값 충돌에 취약 (예: 응답 id=1과
  바인딩 값 "1") — Phase 0 케이스에서는 안전하나, Phase 1에서 응답 필드 ↔ 컬럼
  매핑 기반으로 강화 예정.
- COMPUTED origin 처리 규칙(docs/04)은 빌더가 COMPUTED를 아직 판정하지 않으므로
  미구현. 빌더와 함께 Phase 1에서 도입.
- 숫자형 API_PARAM은 치환하지 않음 (path constraint 부재 상태에서 unique 숫자
  치환은 검증 실패 위험 — docs/04의 "constraint 충족 범위 안에서" 원칙의 보수적 해석).

## Phase 1 개정 (1.5 — 다중 path 합성)

| 규칙 | 결정 | 근거 |
|---|---|---|
| 다중 path 출력 단위 | **path당 테스트 클래스 1개** (`<base>_<S{status}_{n}>`) | path마다 fixture/cleanup이 달라 클래스 분리가 단순·견고. `pathId` 생략 시 endpoint 전 path |
| status-aware 픽스처 | **2xx path만 사전 INSERT 합성**. 4xx path는 치환 변수만 유지 | 404는 "사전 데이터 부재"가 재현 조건 — 픽스처를 넣으면 404가 201로 바뀌는 자기모순. 치환된 testId-unique 값은 DB에 없음이 보장되어 404가 결정적 |
| SERIAL/IDENTITY PK | 픽스처 INSERT에서 제외 | 고정값 INSERT는 시퀀스 충돌·병렬 불안전 |
| FK 부모 행 | 자식 seed 전에 같은 변수 값으로 재귀 seed (부모 먼저 INSERT, 자식 먼저 DELETE) | FK 제약 위반 방지. orders 픽스처가 users 행을 전제 |
| 응답 단언의 보수성 | 2xx 픽스처 삽입으로 응답 값이 캡처 시점과 달라질 수 있음(예: count) → LITERAL 일치만 equalTo, 그 외 notNullValue 유지 | 픽스처-응답 상호작용에 안전 |

## GraphRagClient

도구 1의 graph.json 포맷 자체를 계약으로 사용하는 `FileGraphRagClient`.
모듈 간 코드 의존 없음 (test-generator는 graph-rag-builder를 import하지 않는다).
HTTP query API는 Phase 1에서 같은 인터페이스의 구현체로 추가.
