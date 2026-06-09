# Phase 0 SUT — samples/order-service

## 진행 내용

- Spring Boot 3.5 + Spring Data JPA + Postgres 전용 (인메모리 DB 없음)
- 도메인: `users`(부모) ← `orders`(자식 FK, IDENTITY PK)
- `POST /api/orders`: 201(`{id,status:"PENDING"}`) / 404(user 없음) / 400(검증 실패)
- `ddl-auto=create` (기본, env `DDL_AUTO`로 변경 가능) — 빌더의 스키마 추출 전제
- actuator health 노출 (빌더의 기동 폴링용)
- Dockerfile + `bootJar`(`order-service.jar`)

## 검수

- `./gradlew :samples:order-service:test` GREEN (Testcontainers postgres:15, 3건)
- `bootJar` 산출 확인

## 메모 (환경 이슈)

- Docker Engine 29는 구버전 Docker API(<1.40)를 거부 → docker-java가 1.32로
  호출해 400 발생. 루트 build.gradle.kts에서 모든 Test JVM에
  `api.version=1.44` 시스템 프로퍼티 주입으로 해결.
