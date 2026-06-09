# 의사결정: 분석 환경은 "운영 jar 외부 프로세스 + env 주입"

날짜: 2026-06-10 / 단계: 0.6

## 원안 (docs/02, 03)

분석 환경에서 Spring TestContext로 SUT를 in-process 부팅.

## 결정

SUT의 **운영 jar를 자식 프로세스로 실행**하고, 설정은 환경변수로만 주입한다.

- DB: Testcontainers Postgres (운영 동일 DBMS — 원안 유지)
- datasource/포트: `SPRING_DATASOURCE_*`, `SERVER_PORT`
- Hibernate SQL 캡처: `SPRING_APPLICATION_JSON`으로
  `logging.level.org.hibernate.SQL=DEBUG`, `logging.level.org.hibernate.orm.jdbc.bind=TRACE`
- 기동 확인: actuator health 폴링 (타임아웃 90s)

## 근거

- TestContext in-process 부팅은 SUT의 클래스패스/Spring 버전을 빌더 JVM에
  끌어와야 한다. 대상이 Java 8 + SB2 ~ Java 21 + SB3까지 걸치는 요구사항(docs/01)과
  근본적으로 충돌 (빌더는 Java 17 고정).
- 외부 프로세스 방식은 SUT 소스/빌드 무수정 원칙을 그대로 지키면서 SUT의
  Java/Spring 버전에 완전히 비의존.
- 이전 시도는 같은 문제를 private javaagent(jdbc-intercept-agent)로 풀었으나
  외부 private 의존이 재현성을 해쳤다. env 주입 + 로그 파싱은 의존성이 0이다.

## 함정 기록

- `LOGGING_LEVEL_ORG_HIBERNATE_SQL` 같은 env 상대 바인딩은 로거 이름 대소문자를
  잃어 **동작하지 않는다** (`org.hibernate.SQL`은 case-sensitive).
  `SPRING_APPLICATION_JSON` 주입으로 해결.
- Docker Engine 29+는 구버전 Docker API(<1.40)를 거부 → docker-java에
  `api.version=1.44` 시스템 프로퍼티 필요 (CLI/테스트 양쪽 설정).

## 한계와 복귀 조건

- 로그 파싱 기반 SQL 캡처는 Hibernate(JPA) 한정. MyBatis는 Phase 1에서
  Interceptor 또는 datasource-proxy 래핑(docs/11 참고)으로 확장.
- 실제 빈 와이어링 introspection(L2의 일부)은 Phase 0 범위에서 제외 —
  필요해지는 시점(Spring Security 분석 등)에 actuator 기반 조회를 우선 검토.
