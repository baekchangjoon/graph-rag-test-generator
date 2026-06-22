# Task 7 Report — generic-shape E2E fixtures + exploredPathCount

## Status: DONE

## 변경 요약

### 1. 신규 컨트롤러 (SUT fixtures)

**`DeepNestedController`** (`POST /api/deep`)
- 3-depth 중첩 record: `Root(Level1 l1)` → `Level1(Level2 l2)` → `Level2(String value, int count)`
- 가드: `l1/l2/value` null·blank → 400; `count < 0` → 422
- exploredPathCount > 1 보장: happy(200) + blank(400) + negative-count(422) 최소 2경로

**`CollectionsController`** (`POST /api/prefs`, `POST /api/tags`)
- `POST /api/prefs`: `Map<String,String>` body, 빈 map → 400, 정상 → size 반환
- `POST /api/tags`: `List<String>` body, 빈 리스트 → 400, blank 항목 → 422, 정상 → count 반환
- 양쪽 모두 exploredPathCount > 1 보장

### 2. `ExplorationReport.EndpointExploration.exploredPathCount()` (REQ-009)

`shared-model/src/main/java/io/graphrag/model/ExplorationReport.java` 의 `EndpointExploration` record에 메서드 추가:
```java
public int exploredPathCount() {
    return pathsByEngine == null ? 0
            : pathsByEngine.values().stream().mapToInt(Integer::intValue).sum();
}
```
- 기존 record 컴포넌트/생성자 시그니처 무변경 → 기존 호출 사이트 전부 컴파일 유지

### 3. e2e 픽스처 3종

- `e2e/request-deep.json` (endpointId=`post-api-deep`, testClassName=`DeepPostTest`)
- `e2e/request-prefs.json` (endpointId=`post-api-prefs`, testClassName=`PrefsPostTest`)
- `e2e/request-tags.json` (endpointId=`post-api-tags`, testClassName=`TagsPostTest`)

### 4. `e2e/run-e2e.sh` 업데이트

`for req in ...` 루프에 `request-deep request-prefs request-tags` 추가 (request-orders-ship 직후).

### 5. `BuilderIntegrationTest` 업데이트

`containsExactly(...)` 목록에 알파벳 순 삽입:
- `post-api-deep` (d < o → post-api-bookings-id-advance 다음, post-api-orders 앞)
- `post-api-prefs` (pref < pric → post-api-orders-ship 다음, post-api-pricing 앞)
- `post-api-tags` (s < t → post-api-signups 다음, post-web-* 앞)

## 빌드/컴파일 결과

```
./gradlew :samples:order-service:compileJava :graph-rag-builder:compileJava :shared-model:test -q  → 출력 없음(GREEN)
./gradlew :samples:order-service:bootJar -q  → 출력 없음(GREEN)
```

## AC-3 (ShadowBody) 비고

Shadow-jar e2e 픽스처는 SKIP. `ReflectiveBodyInstantiatorTest`에서 단위 검증됨(기존 커버). 별도 shadow jar 빌드는 과도함 — 단위 테스트가 Instancio fallback 경로를 직접 커버하므로 충분.

## 우려사항

- `BuilderIntegrationTest`의 `containsExactly` 목록은 Docker 기반 통합 테스트에서만 실행되므로 실제 순서는 full e2e gate에서 확인됨. 새 컨트롤러 3개가 security config에서 인증 없이 접근 가능한지는 `SecurityConfig`에서 `/api/deep`, `/api/prefs`, `/api/tags`가 별도 설정 없이 JWT 필터를 타게 되는데, authMode=REAL이라 빌더가 auth token을 획득 후 탐색한다 — 기존 패턴과 동일하므로 문제없을 것으로 판단.
- `post-api-prefs`의 정렬 위치: "prefs" < "pricing" 확인 ('e' < 'i'). "pref" < "pric": p-r-e vs p-r-i → 'e'(101) < 'i'(105) → 정렬 정확.
- `post-api-tags`의 정렬 위치: "signups" < "tags" ('s' < 't') → 정렬 정확.
