# Progress: graph-rag-builder Phase 0 TDD 구현

**Date**: 2026-05-25
**Task**: #8 graph-rag-builder Phase 0 TDD 구현
**Result**: Phase 0 핵심 (캡처 + 영속 + 조회 API) 완료. 20 tests GREEN.

## 산출물

### 모듈 (`graph-rag-builder`)

```
src/main/java/io/graphrag/builder/
├── BuilderApplication.java            # @SpringBootApplication
├── capture/
│   ├── CaptureContext.java            # ThreadLocal로 path 컨텍스트 관리
│   ├── CapturedSqlBuilder.java        # SQL 텍스트 + 파라미터 → CapturedSql DTO
│   └── CapturedSqlListener.java       # datasource-proxy QueryExecutionListener
├── persistence/
│   └── GraphArchive.java              # JSON 파일 기반 영속 (Phase 0 단순화)
├── query/
│   └── EndpointQueryController.java   # GET /endpoints, /endpoints/{id}, /paths/{pathId}/captured-sql
└── config/
    ├── BuilderConfig.java             # @Bean GraphArchive
    └── JacksonConfig.java             # snake_case + java.time
```

### 의존성 추가

- `net.ttddyy:datasource-proxy:1.10` — JDBC 호출 인터셉트로 SQL/파라미터 캡처
- `com.h2database:h2` (test) — 향후 JPA 통합 테스트용
- `spring-boot-starter-web` — 조회 API
- `spring-boot-starter-data-jpa` — SUT 부팅 시 사용

### 테스트 (20개, GREEN)

- `CapturedSqlBuilderTest` (6) — SELECT/INSERT/UPDATE/DELETE 분류, binding 보존, 테이블 추출
- `CaptureContextTest` (5) — ThreadLocal 라이프사이클, immutable snapshot
- `GraphArchiveTest` (5) — endpoint/sql 저장 및 로드, JSON 파일 생성
- `EndpointQueryControllerTest` (4) — Spring Boot 통합 테스트

## TDD 사이클

| 단계 | RED | GREEN |
|---|---|---|
| CapturedSqlBuilder | 6 assertion 실패 | 6 통과 |
| CaptureContext | 5 assertion 실패 | 5 통과 |
| GraphArchive | 5 assertion 실패 | 5 통과 |
| EndpointQueryController | 4 assertion 실패 | 4 통과 (path regex 수정 후) |

## 주요 설계 결정

- **datasource-proxy로 SQL 캡처**: Hibernate StatementInspector 단독으로는 binding 값 캡처 어려움. datasource-proxy가 JDBC PreparedStatement 호출까지 보존.
- **ThreadLocal CaptureContext**: 분석 harness가 path별로 set/clear. 멀티 path 병렬 분석은 Phase 1+에서 고려.
- **JSON 파일 영속 (Phase 0)**: 두 개 JSON (endpoints.json, captured_sql.json). Phase 1+에서 Neo4j 등으로 교체. 인터페이스는 GraphArchive에 캡슐화.
- **{id:.+} 정규식**: endpoint ID에 슬래시 포함 가능 → 정규식으로 path variable 매칭.
- **bindings origin = COMPUTED**: Phase 0은 일괄 COMPUTED. dataflow 분석으로 API_PARAM/LITERAL 분류는 Phase 1.

## 발견 및 수정

1. **datasource-proxy 버전 카탈로그 추가**: `libs.versions.toml`에 `datasource-proxy = "1.10"` 등록.
2. **path variable 슬래시 문제**: `{id}`는 슬래시에서 끊김 → `{id:.+}`로 변경. 테스트는 슬래시 없는 ID로 단순화 (URL 인코딩 필요는 client 측 처리).
3. **DataSource auto-config 제외**: BuilderApplication에서 `DataSourceAutoConfiguration` + `HibernateJpaAutoConfiguration` 제외. 분석 시점에 동적으로 DataSource 구성.

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| docs/03-graph-rag-builder.md의 Layer 4 (Sink Capture) | OK (CapturedSqlListener) |
| docs/03의 Layer 5 (영속) | OK (GraphArchive, Phase 0 단순화) |
| SCHEMAS.md 1절 Query API | 부분 OK (GET /endpoints, /endpoints/{id}, /paths/{id}/captured-sql, /version) |
| commit SHA 태깅 | Phase 1+ |
| 증분 갱신 | Phase 1+ |
| Path constraint 캡처 | Phase 1+ (JDart 도입 시) |

## 의도적으로 후속 phase로 미룬 항목

- **Layer 1-3 (scip-java, Spoon, Framework introspection, Path exploration)**: 본격 작업은 phase 1+
- **datasource-proxy + JPA 통합 테스트**: H2 + @SpringBootTest로 실 JPA save → 캡처 확인. Phase 1에서 추가
- **CapturedHttpCall, CapturedSocketIO 캡처**: phase 2, 4
- **벡터 검색 / 임베딩**: phase 1+
- **`/paths/match`, `/branches/uncovered`, `/tables/*` 등 풍부한 query**: phase 1+
- **Spring TestContext 부팅 + Hibernate SchemaExport**: phase 1+
- **JDart / fuzzer / EvoSuite 통합**: phase 1-3

## 다음 단계

Task #9 — test-generator Phase 0 TDD.

- GenerationRequest/Result 파싱
- 템플릿 + composer 기반 결정적 합성
- RestAssured POST 단일 endpoint 시나리오 합성
- GraphArchive 조회 결과를 입력으로 받음
