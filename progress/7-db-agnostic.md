# 7-db-agnostic — DB 비종속 분석 환경

날짜: 2026-06-14

## 진행 내용

- `ComposeInspector` (B1): SUT의 `docker-compose.yml`을 파싱해 DB 서비스의
  type(postgres/mysql/mariadb)·image·포트·credentials를 `DbConfig`로 반환
- `SqlDialect` (B2): DB 타입별 멱등 INSERT (`ON CONFLICT DO NOTHING` / `INSERT IGNORE`)
- `AnalysisEnvironment` (B3): Postgres 고정 `PostgreSQLContainer`를 제거하고
  `JdbcContainers.forConfig(DbConfig)`로 교체 — `JdbcDatabaseContainer` 공통 인터페이스
  유지. Postgres/MySQL/MariaDB 동적 선택
- `BuilderCli` (B4): `--postgres-image` 제거, `--sut-compose <path>` 신규.
  compose 없으면 Postgres 기본값 유지 (하위호환)

## 검수

- `ComposeInspector` 단위: postgres/mysql/mariadb 파싱 케이스 GREEN
- `AnalysisEnvironment` 통합: 실 Testcontainers DB로 schema DDL + seed INSERT 검증
