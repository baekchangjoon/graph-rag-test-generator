# 의사결정: DB 타입/creds를 SUT compose에서 탐지 + 격리된 Testcontainers 컨테이너 사용

날짜: 2026-06-14 / 단계: Phase 7 (B1–B4)

## 배경

기존 `AnalysisEnvironment`는 Postgres 고정(`PostgreSQLContainer`). 실제 SUT는
MySQL·MariaDB를 쓸 수 있고, Postgres 가정이 박힌 채로 외부 SUT를 지원할 수 없다.

## 결정

SUT의 `docker-compose.yml`에서 DB 서비스의 image/port/credentials를 탐지해
`DbConfig`로 반환(`ComposeInspector`). 분석 환경은 그 타입의 Testcontainers 컨테이너
(`JdbcContainers.create(DbConfig)`)를 띄운다. SUT compose DB는 재사용하지 않는다.

## 검토한 대안

| 접근 | 평가 |
|---|---|
| **A (채택): 탐지 + 격리 컨테이너** | SUT compose 수정 불필요, 분석 격리 유지, 멱등성 보장 |
| B: SUT compose를 그대로 `docker-compose up` | 탐색 중 데이터 오염, 포트 충돌, teardown 복잡. SUT가 여러 서비스를 올리면 불필요한 부팅 포함 |

## 근거

- 분석은 SUT를 재배포·재초기화하며 반복 실행한다. 전용 Testcontainers 컨테이너는
  테스트마다 clean-state를 보장하고 포트 충돌이 없다.
- SUT compose는 파싱용으로만 사용 — SUT 무수정 원칙 유지.
- `JdbcDatabaseContainer` 공통 인터페이스로 Postgres/MySQL/MariaDB를 동일 코드로 처리.
  신규 DB 타입 추가는 `JdbcContainers` 팩토리에만 케이스를 추가하면 된다.

## 오버라이드 플래그

- `--db-service <name>`: multi-DB compose에서 사용할 DB 서비스를 지정
  (`ComposeInspector.detectDb(path, preferredService)`). 미지정 시 services 순서상 첫 DB 서비스.
- `--db-image <image>`: 탐지된 image를 덮어쓴다 (`BuilderCli`).

## 한계

- compose + DB 서비스 필수, 없으면 실패: `--sut-compose`는 required(`BuilderCli`가 throw),
  `ComposeInspector.detectDb`는 DB 서비스가 없으면 throw. **폴백 없음.**
  (갱신 2026-06-14: 기존 "Postgres 기본값 폴백" 서술은 코드와 불일치 — 실제로는 미존재.)
- 현재 지원: Postgres, MySQL, MariaDB. Oracle·SQLServer는 미지원.
- DB 탐지는 image 이름 매칭 휴리스틱 — `my-custom-postgres:latest` 같은 사내 이미지는
  타입이 image 이름으로 결정된다(`postgres`/`mysql`/`mariadb` 부분 문자열).
