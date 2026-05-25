# Open Decisions

구현 시작 전 사용자 결정이 필요한 항목들입니다. 그룹별로 정리. 답변은 이 파일에 메모해도 좋고 별도 응답으로 알려주셔도 됩니다.

각 항목에 **권장 default**를 적어두었습니다. 별다른 의견이 없으시면 default로 진행하겠습니다.

---

## A. 구현 언어/빌드 (Phase 0 진입 전 필수)

### A1. 빌드 시스템

선택지:
- (default) **Gradle 8 멀티모듈 (Kotlin DSL)**
- Maven 멀티모듈
- Bazel

근거: 어차피 대상 프로젝트들이 Maven/Gradle 혼재. 우리 도구 자체는 모듈 분리가 명확해 Gradle 멀티모듈이 깔끔.

### A2. 구현 언어

선택지:
- (default) **Java 17** (도구 자체)
- Java 21 (도구 자체)
- Kotlin
- Java 17 + 일부 Kotlin (필요 모듈만)

근거: 도구가 다루는 SUT는 Java 8/21 혼재지만, 도구 자체는 모던 Java 가능. testlib **api**만 Java 8 호환 (SUT 테스트 환경에서 쓰일 수 있어), 나머지는 Java 17.

### A3. 테스트 프레임워크

선택지:
- (default) **JUnit 5 + AssertJ + Testcontainers**
- Spock (Groovy)
- 기타

---

## B. 저장소 인프라 (Phase 1 진입 전 필수, Phase 0은 임시 파일로 가능)

### B1. Graph store

선택지:
- (default) **Neo4j Community** (5M 처리 검증, Cypher 친화)
- Memgraph (in-memory, 빠름)
- Kuzu (임베디드, 단일 노드)

근거: 5M 라인 처리에 검증된 Neo4j. 클러스터 가능. Cypher는 LLM 친화.

### B2. Vector store

선택지:
- (default) **pgvector** (개발 편의, 단일 Postgres 인스턴스에 통합)
- Qdrant (전용, 성능 우위)
- Milvus / Weaviate

근거: 추가 인프라 부담 최소. Postgres가 어차피 다른 곳에도 쓰임.

### B3. Embedding 모델 (Phase 1 이후)

선택지:
- (default) **Voyage code-3** 또는 **로컬 CodeBERT/UniXcoder**
- OpenAI text-embedding-3
- 자체 fine-tune

근거: 코드 임베딩은 code-specific 모델이 일반 모델보다 우위. 비용 고려.

---

## C. Phase 0 PoC 대상 (Phase 0 진입 전 필수)

### C1. PoC 대상 프로젝트 접근 가능한가

- Java 21 + SB3, 10만 라인 프로젝트의 소스/JAR 접근 가능?
- 가능하면: 해당 프로젝트의 어떤 단순 endpoint 하나 (JPA만, 외부 호출 없음, 인증 단순)를 Phase 0에 사용할까요?
- 불가능하면: 우리가 demo용 SUT를 만들어야 함 (Spring Boot 3 + JPA + Postgres + 단일 POST endpoint). 작업량 ~ 4시간.

(default) **demo SUT를 만들어 시작** → 실제 프로젝트는 Phase 0 끝나고 적용

### C2. 운영 DBMS

선택지:
- (default) **PostgreSQL 15**
- MySQL 8
- Oracle
- 두 프로젝트가 다른 DBMS 사용 → 둘 다 지원

근거: 도구 1의 분석 환경/도구 2의 테스트 환경이 운영과 동일 DBMS여야 함. Phase 0은 단일 DBMS로 시작.

---

## D. SUT의 propagation (Phase 2 이후 영향)

### D1. 두 프로젝트의 트레이싱 인프라 현재 상태

- Java 21 + SB3 (10만 모던): Sleuth/Micrometer Tracing/OTEL 중 무엇이라도 깔려있는지?
- Java 8 + SB2 (5M 레거시): 트레이싱 도구 부재일 가능성 ↑

→ 어느 쪽이든 **docker-compose에서 OTEL javaagent를 강제 부착** 하므로 큰 영향 없음. 단 이미 다른 propagation이 있으면 충돌 가능성 검토 필요.

(default) **모르겠다면 OTEL javaagent 강제 부착 가정**

---

## E. 인증 (Phase 0 영향)

### E1. PoC 단계에서

선택지:
- (default) **auth_mode = disabled** (Phase 0은 인증 끄고 단순화)
- real (실제 토큰 발급 흐름 포함)

근거: PoC 복잡도 최소화. 인증은 Phase 1 이후 추가.

### E2. 실 운영 프로젝트의 인증 방식 (Phase 1 이후)

- OAuth2 / JWT / Form login / Custom 중 무엇?
- 두 프로젝트가 다를 가능성 → 어댑터 다중 구현

(default) **확인 필요. 우선 Phase 0은 disabled로 진행**

---

## F. 외부 시스템 (Phase 2 이후 영향)

### F1. OpenAPI / 스키마 사양 보유 여부

이전 답변에서 "있는 것을 default, 없어도 처리"로 합의됨. 확인:
- 두 프로젝트의 외부 의존 중 OpenAPI 사양 보유 비율 대략?
- (대답 불요) 도구는 두 경우 모두 처리.

### F2. Socket 프로토콜 사양

이전 답변에서 "없음. 추후 추가 가능하도록 자리 비움" 합의됨.

---

## G. 모니터링 (Phase 0 영향 적음)

### G1. test-state-dashboard 인증

- 단일 사내 인프라면 무인증 + 사내 네트워크 격리만으로 충분?
- 외부 노출 가능성 있으면 simple basic auth 추가?

(default) **무인증으로 시작. 필요 시 어댑터로 추가**

### G2. Dashboard 영속화

- in-memory만으로 시작? 또는 SQLite/Postgres 영속?

(default) **in-memory로 시작. 영속화는 옵션으로 SPI**

---

## H. 운영적 사항

### H1. 라이선스

선택지:
- (default) **Apache 2.0** (사내 공유 + 외부 의존 라이브러리들과 호환)
- MIT
- 비공개 (라이선스 표기 없음)

### H2. 리포지토리 형태

선택지:
- (default) **monorepo** (모든 모듈 한 리포)
- multi-repo

근거: 단계별 통합 E2E 빈도 ↑ → monorepo가 협업 비용 낮음.

### H3. CI/CD

선택지:
- (default) **GitHub Actions** + Docker로 통합 테스트
- Jenkins
- 기타

### H4. Git 초기화

현재 `git` 미초기화 상태. 초기화 시점:
- (default) **프로젝트 골격 셋업 시점에 `git init`**
- 또는 사용자가 직접 초기화 후 알림

---

## I. PoC endpoint 명세 예시 (구현 시 사용)

Phase 0 demo SUT를 만들 경우의 명세 가안:

```
POST /api/orders
  request body: {
    "userId": string,
    "amount": int,
    "type": "EXPRESS" | "STANDARD"
  }
  
  성공: 201 Created + {"orderId": "...", "status": "PENDING"}
  실패: 400 if amount <= 0, 404 if user not found
  
  내부 동작:
    1. SELECT * FROM users WHERE id = ?
    2. INSERT INTO orders(user_id, amount, type, status) VALUES (?, ?, ?, 'PENDING')
    3. 외부 호출 / 소켓 통신 없음 (Phase 0)
  
  인증: disabled (Phase 0)
```

이 명세 OK? 수정/추가?

---

## 답변 요청

위 A~I 각 항목에 대해:

1. **default 수용**이면 "전부 default"라고만 답해주셔도 됩니다.
2. **특정 항목 변경 희망**이면 항목 번호 + 선택지만 알려주세요.
3. **C1**, **D1**, **E2**, **F1** 같은 **사실 확인 항목**은 답변 부탁드립니다.

답변 받는 즉시 task #3 (프로젝트 골격 셋업) 부터 진행하겠습니다.
