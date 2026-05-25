# 01 — 목적과 범위

## 무엇을 만드는가

Java/Spring으로 작성된 기존 애플리케이션을 대상으로,
**블랙박스 REST API 테스트에 필요한 자산**(테스트 코드 + 테스트 데이터 + mock 데이터)을
**결정적으로 생성**하는 시스템.

산출물 예시:

- `OrdersPostTest.java` (RestAssured 기반 JUnit5 테스트)
- DB 사전 데이터 INSERT 시퀀스 (운영 동일 DBMS 기준)
- 외부 HTTP 의존을 대체하는 WireMock 스텁 JSON
- 외부 Socket 통신을 대체하는 byte 시퀀스 mock 데이터

## 왜 만드는가

- 기존 테스트가 신뢰 불가능 (실패하거나 관리되지 않음)
- 매번 LLM으로 소스코드를 분석해 테스트를 만드는 비용이 큼
- 사전에 코드의 사실을 그래프로 모아두고, 그 사실로부터 결정적으로 테스트를 합성하면 비용/정확도가 모두 개선됨

## 대상 사용자

- 레거시 API에 대해 회귀 테스트 셋을 갖고 싶은 팀
- 외부 의존(다른 시스템 HTTP, socket 등) 때문에 단위/통합 테스트 작성이 어려운 팀
- 신뢰할 수 없는 기존 테스트를 대체할 자산이 필요한 팀

## 범위 (in-scope)

- Java 8 + Spring Boot 2 ~ Java 21 + Spring Boot 3
- Maven, Gradle 빌드 시스템
- MyBatis (XML / annotation), JPA (Hibernate)
- HTTP 클라이언트: RestTemplate, WebClient, OpenFeign, OkHttp
- Socket: Netty 기반, 일부 raw `java.net.Socket`
- WebSocket: Spring STOMP, JSR-356, 직접 핸들러
- 결정적 합성 (LLM은 외부 오케스트레이터로만)

## 범위 외 (out-of-scope)

- SUT 소스코드 수정 (분석 도구는 외부에서 부착)
- 운영/스테이징 실 트래픽 데이터 사용
- 화이트박스 단위 테스트 자동 생성
- UI 테스트, Selenium 등
- 비-JVM 언어 (Kotlin은 후순위로 검토)

## 대상 프로젝트 두 가지

### A. Java 8 + Spring Boot 2, 약 500만 라인의 레거시

- 멀티 프로젝트, Maven/Gradle 혼재
- MyBatis 비중 큼 (예상), XML mapper 많음
- 외부 통신에 raw socket / Netty 포함 가능성
- 기존 테스트는 신뢰 불가능 → 무시
- 큰 인프라 투자 필요. PoC 후 단계적 이식.

### B. Java 21 + Spring Boot 3, 약 10만 라인의 현대화 프로젝트

- 비교적 깔끔, Jakarta EE 네임스페이스
- 빠른 반복 가능. PoC 첫 대상으로 적합.
- 여기서 검증된 파이프라인을 A에 이식.

## 비목표 / 의식적인 트레이드오프

- **환각 위험을 사람의 템플릿/규칙 작성 부담으로 옮긴다.** 도구 2 안에 LLM이 없으므로, 변환 규칙과 템플릿을 사람이 사전에 마련.
- **정적-only가 아닌 "도구가 직접 실행" 허용.** 단, 외부 데이터(실 트래픽, 운영 로그)는 사용하지 않음.
- **그래프 캡처 비용은 사전에 한 번 / 증분.** 쿼리는 비교적 가볍게.

## 성공 기준 (Phase별 측정)

- **Phase 0**: 단일 JPA-only endpoint에 대해 build → graph → generate → run → pass 한 사이클 성공
- 이후 phase는 phase별 PoC 통과율 정의 (`docs/09-implementation-roadmap.md`)
