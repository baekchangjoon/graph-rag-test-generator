# 7-multi-method — 다중 HTTP method 인덱싱

날짜: 2026-06-14

## 진행 내용

- `EndpointIndexer`: `@GetMapping/@PutMapping/@DeleteMapping/@PatchMapping` +
  `@RequestMapping(method=…)` 상수 추가. 단일 POST 체크를 method→HttpMethod 매핑 순회로 일반화
- 파라미터 종류 확장: `@PathVariable`→`ParamKind.PATH`, `@RequestParam`→`ParamKind.QUERY`
  추가 (기존 `@RequestBody`→`BODY`와 공존)
- `authRequired` 휴리스틱: 하드코딩 `false` 제거. `AuthConfig`가 주어지면
  `loginPath`(+ 명시 public 경로)를 제외한 전 경로 = `true`
  (Spring Security 정적 파싱은 보류 — `docs/decisions/auth-required-heuristic.md`)

## 검수

- `EndpointIndexer` 단위: GET/PUT/DELETE + `@PathVariable`/`@RequestParam`/authRequired
  케이스 GREEN
- 기존 POST 경로 byte-identical (하위호환)
