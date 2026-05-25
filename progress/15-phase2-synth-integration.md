# Progress: Phase 2 합성기 통합 + GitHub push

**Date**: 2026-05-26
**Tasks**: #29, #30, #31
**Result**: HTTP capture를 TestSynthesizer까지 자동 통합 + GitHub repo 생성/푸시

## GitHub

레포 생성: https://github.com/baekchangjoon/graph-rag-test-generator (public, Apache 2.0)

푸시: `main` 브랜치, 모든 진행 커밋 포함.

## 산출

### #29 TestSynthesizer에 HTTP 통합

- `PathContext`에 `capturedHttpCalls` 필드 추가 (호환 생성자 유지)
- `TestSynthesizer.synthesizeMulti`:
  - `hasHttp` 감지 → 조건부 WireMock import + `WireMock.configureFor` in `@BeforeAll`
  - 각 path의 `@Test` 메소드 본문에:
    - `WireMock.reset()`
    - `HttpStubComposer`로 stubFor 코드 삽입
    - 그 후 fixture, API call, cleanup
- 클래스명 도출 강화: kebab/snake-case → PascalCase  
  (`with-inventory` → `WithInventory`)

테스트: `MultiPathHttpSynthesisTest` (5 cases)
- import 조건부 포함
- 혼합 path (HTTP 있는 것 / 없는 것) 정확히 분리
- 생성 코드 javac로 컴파일 통과
- 결정적 출력

### #30 통합 E2E

`Phase2HttpSynthesisE2eTest` (demo-sut):
1. WireMock 서버를 inventory mock으로 부팅
2. demo-sut의 `/api/orders/with-inventory` 실 호출
3. `CapturedSqlListener` + `WireMockHttpRecorder` 동시 캡처
4. `PathContext(path, sqls, httpCalls)` 구성
5. `TestSynthesizer.synthesizeMulti` 호출
6. 생성 코드에 `stubFor`(WireMock), `INSERT INTO orders`(SQL fixture) 모두 포함 확인

→ Phase 2가 컴포넌트 단위에서 **전 사이클 통합**으로 완결됨.

### #31 README 갱신

- repo URL 명시
- Phase별 진행 상태 표
- 빠른 시작 (Phase별 E2E 실행 명령 포함)
- 모듈 구성 + 디렉터리 확장
- docker-compose 운영 환경 안내

## 검증

`./gradlew build`: BUILD SUCCESSFUL.
신규 테스트 (6): `MultiPathHttpSynthesisTest`(5) + `Phase2HttpSynthesisE2eTest`(1).

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| docs/04 합성 방식 C (큰 골격 + 가변 슬롯 프로그램) | OK (HTTP stub 슬롯이 추가됨) |
| docs/03 capture → 도구 2 → 합성 결과까지 | OK (Phase 2 전 사이클 통합) |
| 결정성 + javac 검증 | OK |
| testlib 추상화 | **부분**: 현재는 WireMock client 직접 import. Phase 2+에서 testlib `HttpMockClient` 추상화로 교체 검토 |

## 후속

- testlib `HttpMockClient`를 사용하는 합성 모드 (현재는 WireMock direct)
- Socket mock composer의 TestSynthesizer 통합 (Phase 4 연속 작업)
- WebSocket E2E (Phase 3 완결)
- javaagent 본체 (Phase 5)
- JaCoCo + fuzzer (Phase 1 stretch)
