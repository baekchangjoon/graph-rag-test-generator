# 의사결정: GET read-path — schema+param 휴리스틱 시딩 + RequiredSeed graph 사실

날짜: 2026-06-14 / 단계: Phase 7 (A1, E1–E4, C#3)

## 배경

GET 조회 테스트가 성립하려면 대상 row가 DB에 존재해야 한다. POST 탐색은 builder가
직접 INSERT를 유발하므로 문제없지만, GET은 별도로 시드 row를 준비해야 한다.

## 결정

builder(`ReadInputSynthesizer`)가 탐색 전에 시드를 삽입하고, 사용한 값을
`RequiredSeed` graph 사실(`GraphAsset.seeds`)로 기록한다. generator는 이 사실을 읽어
생성 테스트 첫머리에 동일 INSERT를 재현한다. 탐색과 합성이 동일 시드 기반으로 결정적으로
연결된다.

## 타깃 테이블 결정

path 세그먼트↔테이블명의 단/복수 매칭. 쿼리 파라미터는 스키마의 컬럼명과 매칭.
추론 실패 시 `--read-target <endpointId>=<table>` 수동 override.

## FK 부모 시딩

대상 테이블의 FK 컬럼이 가리키는 부모 테이블을 재귀적으로 추적해 parent-first 순서로
시드. 기존 `SampleInputSynthesizer`의 FK 시딩 로직을 재사용.

## PK 처리 (IDENTITY 컬럼)

path param `{id}`는 고정 값 1로 시드. Postgres IDENTITY 컬럼은 명시 값 INSERT 가능하나
시퀀스가 갱신되지 않아 이후 자동 증가 INSERT가 충돌할 수 있다. 탐색 중 자동 INSERT가
발생하므로 명시 INSERT 직후 `SELECT setval(seq, max(id))` 재동기를 수행.

## 생성 테스트의 시드 순서

`requiredSeedIds`는 FK 그래프 위상 순서로 정렬 후 `INSERT` 코드 블록 생성. 삭제는
역순(`@AfterEach`).

## 알려진 한계 (정직하게)

- **증분 빌드**: `IncrementalPlan`에 `carriedSeeds`가 없다. 클린 파티션 이월 시 시드도
  함께 이월돼야 하나 현재 미구현. 풀빌드는 정상; 증분 빌드 후 read-path 테스트 실행 시
  시드 누락 가능. 별도 과제로 처리.
- **병렬 격리**: PK id=1 고정 + 공유 probe FK → read-path 테스트 클래스 간 `@BeforeEach`/
  `@AfterEach` 동시 실행 시 row 충돌. 현재 sequential 실행(`@TestMethodOrder`)으로
  회피; testId-격리 시드는 별도 강화 과제.
- **타깃 테이블 결정**: path/스키마 휴리스틱. codegraph handler→repository→entity
  call-graph 추적으로 정확도를 높일 수 있으나 현재 미필요.
