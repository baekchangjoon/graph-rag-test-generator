# 문제 브리핑: 시드 타깃 테이블/컬럼 해석 갭 (다음 세션 인계)

- 작성일: 2026-06-15
- 상태: **분석 완료, 미해결** (구현은 다음 세션)
- 선행 머지: PR #28 (`--with-kafka` + 날짜/문자열-PK 시드 수정) — main에 반영됨
- 관련 메모리: `input-discovery-staged-roadmap`, `regression-on-sut-expansion`

---

## 1. 한 줄 요약

빈 DB에서도 by-id 조회 테스트를 만들기 위해 **읽기 전에 DB를 시딩**하는데
(`ReadInputSynthesizer`), 이 시딩이 **REST 리소스명 ≠ 테이블명**이거나 **PK가 아닌
컬럼으로 조회**하는 엔드포인트에서 작동하지 않는다. 그 결과 시드가 비고 조회가 404 →
커버리지가 바닥(mindgraph 5%, analytics 12%, notification 4%). 이것은 빈 DB 탓이
아니라 **시딩 미작동** 탓이며, 사용자가 지적한 "by-id 문제: 없으면 조회 못 함"의
미해결 잔여 케이스다.

---

## 2. 증거 (tainted-spring-msa 전체 실행 결과)

빌더 탐색(seed→read) 커버리지 + 생성테스트 e2e:

| 서비스 | JDK | 인프라 | 엔드포인트 | e2e | 탐색 line/branch | 시드 |
|---|---|---|---|---|---|---|
| community | 8 | MySQL+Kafka | 5 | 25/25 | **62% / 16%** | ✅ 동작 (`/posts`≈`post`) |
| diary | 23 | PG+Kafka | 5 | 8/9 | 38% / 0% | ✅ 동작 (PK=`id`) |
| analytics | 23 | PG+Kafka | 2 | 3/3 | **12% / 25%** | ❌ 미동작 |
| mindgraph | 11 | PG+Redis+Kafka | 2 | 4/4 | **5% / 0%** | ❌ 미동작 |
| notification | 17 | Redis+Kafka | 1 | 2/2 | **4% / 10%** | ❌ Redis 백엔드(SQL 시더 도달 불가) |
| auth-user | 17 | MySQL+Redis | 5 | 5/5 | 11% / 6% | (대부분 인증/쓰기) |
| counseling | 21 | Redis | **0** | N/A | — | WebFlux, 어노테이션 REST 없음 |
| bff-gateway | 21 | — | (4) | N/A | — | 집계 게이트웨이 |

> e2e는 전부 통과하지만, 시드 미동작 서비스는 "404/빈 결과를 어설션"하는
> 테스트라 통과해도 커버리지가 낮다. 통과 ≠ 의미 있는 커버리지.

### graph.json이 보여주는 핵심
mindgraph의 모든 path가 `s404`(s200 없음), seed `NONE`. analytics/notification도 seed `NONE`.

### 그런데 빌더는 진짜 테이블·컬럼을 이미 안다
mindgraph 탐색 중 캡처된 실제 SQL:
```sql
select graphrecor0_.diary_id ... from graph_record ... where diary_id = ?
```
→ 테이블 `graph_record`, 조회 컬럼 `diary_id`가 **graph.sql에 이미 있다.**

---

## 3. 근본 원인 (코드 위치)

`graph-rag-builder/src/main/java/io/graphrag/builder/run/ReadInputSynthesizer.java`

### 원인 A — `resolveTargetTable` 의 경로-문자열 휴리스틱 (line 167–176)
```java
// path 에 테이블명(또는 단수형)이 등장하는 첫 매칭
if (path.contains("/" + name) || path.contains("/" + singular(name))) return table;
```
- mindgraph: path `/internal/graphs/diary/{diaryId}` vs table `graph_record` → 매칭 실패 → 타깃 null → 시드 0.
- analytics: path `/internal/analytics/mood/{userId}` vs table `mood_point` → 실패.
- community가 동작한 건 우연히 `/posts` ⊃ `post` 였기 때문.

### 원인 B — PATH 변수를 무조건 PK로 매핑 (`mapParamToColumn`, line 184–195)
```java
if (param.kind() == ParamKind.PATH) {
    return target.columns().stream().filter(ColumnSchema::primaryKey)... // 항상 PK
}
```
- mindgraph `byUser` (GET `/user/{userId}`)는 **비-PK 컬럼 `user_id`** 로 조회
  (PK는 `diary_id`). PATH를 PK에 매핑하면 엉뚱한 컬럼을 시드 → 여전히 404.
- camelCase→snake (`diaryId`→`diary_id`, `userId`→`user_id`) 변환은 `camelToSnake`
  (line 286)에 있으나, PATH 분기에서는 쓰이지 않고 PK 이름만 본다.

### 원인 C — 비-SQL 백엔드 (notification)
- notification은 알림을 **Redis**에 저장. SQL 시더로는 도달 불가 → 근본적으로
  다른 시딩 경로(Redis 시드) 필요. (별도/후순위)

---

## 4. 제안 해결 방향

**핵심 아이디어: 경로-문자열 추론을 버리고, 탐색이 캡처한 SELECT SQL을 시드의
근거로 삼는다.** FROM 절 → 시드 테이블, WHERE 절 컬럼 → 조회 컬럼(= PATH 변수가
시드해야 할 컬럼). 이 정보는 이미 graph(`sql`)에 있다.

단, 닭-달걀: 현재 `ReadInputSynthesizer`는 **탐색 전에** 시드를 만든다(시드가 있어야
읽기가 성공). 캡처 SQL은 탐색 중/후에 생긴다. 해결 후보:
1. **2-pass 탐색**: 1차(시드 없이) 호출 → 404라도 SELECT SQL 캡처 → FROM/WHERE로
   시드 합성 → 2차 호출(시드된 상태)로 본 탐색. (가장 견고, 구현 비용 큼)
2. **휴리스틱 보강(1-pass)**: `resolveTargetTable`을 토큰-오버랩으로 확장
   (`graphs`→`graph_record`는 토큰 `graph` 공유, `mood`→`mood_point`는 `mood` 공유)
   + PATH 변수를 PK 고정이 아니라 "이름이 일치하는 컬럼 우선, 없으면 PK"로
   (`diaryId`→`diary_id` 우선, 그게 PK든 아니든). (저비용, 일부 케이스 잔존 가능)
3. **혼합**: 2번을 기본으로, 캡처 SQL이 있으면 그걸로 보정(점진 도입).

추천: **2번 먼저(빠른 효과), 그다음 1번을 정공법으로.** 단 결정은 다음 세션에서
스펙/플랜으로 확정 (3-모델 리뷰 포함).

---

## 5. 수용 기준 (E2E / acceptance)

빈 DB·병렬 전제. 다음이 모두 성립해야 "해결":
1. mindgraph `byDiary`(PK 조회)·`byUser`(비-PK 조회) 모두 **시드 후 200**, 그래프
   직렬화 코드까지 커버 → 탐색 line 커버리지 5% → 유의미 상승(목표 ≥ 40%).
2. analytics `mood/{userId}` 시드 후 200, 집계 조회 경로 커버.
3. order-service e2e **45/45 유지**(회귀 없음), 기존 단위테스트 GREEN.
4. 회귀 가드: `ResolveTargetTable`/`mapParamToColumn`에 대해 resource명≠table명,
   비-PK 조회, camelCase 매핑 케이스 단위테스트 추가.
5. (후순위) notification: Redis 시드 경로 — 별도 작업으로 분리.

---

## 6. 재현 방법

빌더/제너레이터/e2e 실행 스크립트(로컬, `.work/`는 gitignore):
```
.work/run-msa-builder.sh <mindgraph|community|analytics|notification>   # 빌더(+탐색 커버리지)
.work/run-msa-e2e.sh    <mindgraph|community|analytics|notification|diary>  # 생성테스트 e2e
```
서비스 소스: `~/github_tainted-spring/tainted-spring-<svc>` (이기종 JDK 8/11/17/21/23).
플랫폼 compose: `~/github_tainted-spring/tainted-spring-platform/docker-compose.yml`.
JDK 경로·DB·infra 매핑은 두 스크립트 상단 case 문 참조.

빌더 단독 빠른 확인:
```
./gradlew :graph-rag-builder:run --args="build \
  --sut-src <svc>/src/main/java --sut-resources <svc>/src/main/resources \
  --sut-jar <svc>/.../*.jar --out /tmp/x --sut-id <svc> \
  --sut-compose <platform compose> --db-service postgres \
  --sut-java-home <JDK> --with-kafka [--with-redis] --budget-requests 60"
# 로그의 'exploration coverage' 와 /tmp/x/graph.json 의 paths[].status / seeds 확인
```

---

## 7. 관련 파일

- `graph-rag-builder/.../run/ReadInputSynthesizer.java` — `resolveTargetTable`(167),
  `mapParamToColumn`(184), `synthesize`(52), `camelToSnake`(286), `keyProbe`(270).
- `graph-rag-builder/.../run/EndpointExplorationRunner.java` — 시드 적용/탐색 오케스트레이션.
- graph의 `sql` 엔트리 (CapturedSql) — FROM/WHERE 추출 대상.
- 테스트: `graph-rag-builder/.../run/ReadInputSynthesizerTest.java`.
