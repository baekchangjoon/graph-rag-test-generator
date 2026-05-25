# Progress: shared-model TDD 구현

**Date**: 2026-05-25
**Task**: #4 shared/model 모듈 TDD 구현
**Result**: 28개 소스 + 13개 테스트 클래스, 전부 GREEN

## 산출물

### 도메인 객체 (Phase 0 필수 일체)

| 카테고리 | 클래스 |
|---|---|
| 인프라 | `JsonMappers` (snake_case + java.time + unknown fields ignored) |
| Enum | `HttpMethod`, `BindingOrigin`, `CapturedSqlType`, `CapturedSqlSource`, `PathExplorerKind`, `DashboardEventType` |
| 값 객체 | `Endpoint`, `SourceLocation`, `Binding`, `Column`, `ForeignKey`, `Table` |
| Path | `PathConstraint`, `SampleInput`, `ExploredPath` |
| 캡처 | `CapturedSql` |
| 대시보드 | `DashboardEvent` + 9개 payload record (`ScopeCreatedPayload`, `ScopeCleanedPayload`, `ResourcesReleased`, `DbRowInsertedPayload`, `DbRowDeletedPayload`, `HttpStubRegisteredPayload`, `HttpStubRemovedPayload`, `SocketSessionOpenedPayload`, `SocketSessionClosedPayload`, `AuthTokenIssuedPayload`) |

### TDD 사이클 기록

각 클래스 또는 클래스 그룹별로 다음 순서 준수:

1. 테스트 작성 → `./gradlew :shared-model:test` 실패 확인 (RED)
2. 최소 구현 → `./gradlew :shared-model:test` 통과 확인 (GREEN)
3. 다음 그룹으로

순서:
1. `HttpMethod` (단독)
2. `BindingOrigin`, `CapturedSqlType`, `CapturedSqlSource`, `PathExplorerKind` (enum 묶음)
3. `JsonMappers` 유틸리티 (snake_case 검증)
4. `Endpoint`, `SourceLocation`, `Binding` (기본 값 객체)
5. `Column`, `ForeignKey`, `Table` (스키마)
6. `PathConstraint`, `SampleInput`, `ExploredPath`
7. `CapturedSql`
8. `DashboardEventType`, `DashboardEvent`, payload records 일체

## 설계 의사결정

- **Java records**: 도메인은 immutable. records가 equals/hashCode/toString 자동 제공.
- **List/Map 방어 복사**: 생성자에서 `List.copyOf`로 불변 보장.
- **Null 처리**: 필수 필드는 `Objects.requireNonNull`. Optional 필드(`pathConstraint`, `originRef`, `exitResponseShape` 등)는 nullable.
- **`Object payload`**: 폴리모픽 payload는 Jackson `convertValue`로 소비자가 타입 변환. SCHEMAS의 `payload: object` 그대로 반영.
- **`@JsonProperty("class")`, `@JsonProperty("default")`**: Java keyword 회피용 필드명 변경 + JSON은 schema 준수.

## 검증

```
$ ./gradlew :shared-model:test
BUILD SUCCESSFUL in 1s
```

전체 테스트 통과:
- HttpMethodTest (3)
- BindingOriginTest (2)
- CapturedSqlTypeTest (2)
- CapturedSqlSourceTest (2)
- PathExplorerKindTest (2)
- JsonMappersTest (4)
- EndpointTest (4)
- SourceLocationTest (3)
- BindingTest (5)
- SchemaTypesTest (5)
- PathTypesTest (5)
- CapturedSqlTest (2)
- DashboardEventsTest (6)

→ 13 test classes, 45+ test methods. All passing.

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| SCHEMAS.md 0절(공통 데이터 모델) Phase 0 필수 항목 일체 구현 | OK |
| 스키마 필드명이 snake_case JSON으로 직렬화 | OK |
| 폴리모픽 payload 처리 (DashboardEvent) | OK (`Object` + `convertValue`) |
| 값 객체 equality/hashCode | OK (record 자동) |
| 모든 immutable | OK (record + 방어 복사) |

## 의도적으로 후속 단계로 미룬 항목

- `CapturedHttpCall` (Phase 2)
- `CapturedSocketIO` (Phase 4)
- `Branch` (Phase 1)
- `PropagationInfo` (Phase 2)
- `AuthRequirement` + `AuthScheme` (Phase 1, real 모드 시)

이유: Phase 0 PoC에 불필요. 해당 phase 진입 시 동일한 TDD 패턴으로 추가.

## 다음 단계

Task #5 — `testlib-api` + `testlib-adapter-noop`.

- SPI 인터페이스 (HttpMockClient, SocketMockClient, JdbcHelper, AuthClient, DashboardReporter)
- TestScope (testId 발급 + cleanup)
- RestAssuredHelper (baggage 헤더 자동 부착)
- noop 어댑터 (Phase 0 환경 — 외부 HTTP/socket/auth 없음)
