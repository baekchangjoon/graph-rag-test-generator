# 10 — 5M 라인 레거시 이식 아키텍처 (Phase 6)

Phase 0-5에서 단일 호스트, 100K 라인 모던 프로젝트 기반으로 검증된 파이프라인을 5M 라인 레거시 (Java 8 + Spring Boot 2)로 이식하기 위한 운영 인프라.

## 핵심 차이

| 항목 | Phase 0-5 (100K 모던) | Phase 6 (5M 레거시) |
|---|---|---|
| 그래프 저장 | 파일 JSON | **Neo4j 클러스터** |
| 인덱싱 빈도 | 매 PR | 야간 풀 + PR 증분 |
| 풀빌드 시간 | 분 단위 | 6-12시간 |
| 노드 수 | 수만 | **수천만~1억** |
| 메모리 | 2-4GB | **64GB+** |
| MyBatis XML 비중 | 적음 | **많음 (60%+)** |
| 외부 socket | 적음 | **빈번 (legacy 인프라)** |

## 인프라 아키텍처

```
                  ┌──────────────────────────┐
                  │  Git repository (5M LOC) │
                  └──────────┬───────────────┘
                             │ commit / PR
                             ↓
                  ┌──────────────────────────┐
                  │  CI/CD trigger           │
                  │  - PR: 증분 분석          │
                  │  - main: 야간 풀빌드      │
                  └──────────┬───────────────┘
                             │
              ┌──────────────┼──────────────┐
              ↓              ↓              ↓
        ┌─────────┐    ┌─────────┐    ┌─────────┐
        │ Worker  │    │ Worker  │    │ Worker  │
        │ 노드 1  │    │ 노드 2  │    │ 노드 N  │
        │(module1)│    │(module2)│    │(module3)│
        └────┬────┘    └────┬────┘    └────┬────┘
             │              │              │
             └──────────────┼──────────────┘
                            ↓
                  ┌──────────────────────────┐
                  │  Neo4j 클러스터 (3+)     │
                  │  - causal cluster        │
                  │  - 야간 백업             │
                  └──────────────────────────┘
                            ↑
                            │ Cypher
                  ┌──────────────────────────┐
                  │  Test Generator clients  │
                  │  (사용자/Claude)         │
                  └──────────────────────────┘
```

## 모듈 단위 파티셔닝

5M 라인은 보통 멀티 모듈 Maven/Gradle:
- 모듈별 worker 노드에 분배
- 각 worker는 자기 모듈의 scip-java + capture만 책임
- 마스터가 결과를 Neo4j에 통합 (전역 호출 그래프 형성)

## 증분 인덱싱 알고리즘

```
PR 변경 파일 목록 → git diff
  ↓
변경 클래스 식별
  ↓
영향 분석:
  - 시그니처 변경 클래스의 호출자 (1-2 hop)
  - 추가/제거된 @Query, MyBatis XML
  - 추가/제거된 endpoint
  ↓
영향 받는 클래스/메소드 재인덱싱
  ↓
Neo4j에 transactional 갱신 (commit SHA 태그 갱신)
```

## 야간 풀빌드

- 매일 새벽 1회 (트래픽 낮을 때)
- 증분 인덱싱의 표류 보정
- 단계:
  1. 신선한 clone
  2. 빌드 + 모든 모듈의 scip-java
  3. 영구 캡처 환경 부팅 (Spring TestContext + Testcontainers)
  4. 알려진 endpoint 전부에 대해 ManualPathExplorer 입력 실행
  5. 결과를 staging Neo4j에 적재
  6. staging ↔ production swap (downtime 최소화)
- 실패 시 production 그래프 유지 + 알람

## raw socket 보강 어노테이션

5M 레거시는 raw `java.net.Socket` 사용 빈도가 높음. 프로토콜 사양 없음. 대응:

```java
@WireFormat(name = "InventoryReserveRequest")
public class InventoryReserveMessage {
    @WireField(offset = 0, size = 4, type = "int_be") private int length;
    @WireField(offset = 4, size = 1, type = "byte") private byte msgType;
    @WireField(offset = 5, size = "length - 5", type = "bytes") private byte[] payload;
}
```

도구 1의 socket capturer가 이 어노테이션을 읽어 ByteLayout 자동 추출. Phase 5의 `ProtocolDecoderRegistry`에 자동 등록.

→ **legacy 코드에 어노테이션만 점진 추가**, 도구 무수정.

## 5M에서 추가로 필요한 도구

- **scip-java**: 멀티 모듈 빌드 지원 + 점진적 인덱싱
- **Neo4j**: causal cluster 3+ 노드. 백업 + 모니터링
- **Soot/SootUp**: 호출 그래프 + dataflow (Phase 1+의 fuzzer/JDart 강화에)
- **JDart 또는 자체 fuzzer**: 분기 자동 탐색 (수천 endpoint를 사람이 manual로 갱신 불가능)
- **Kafka 또는 메시지 큐**: 분산 worker → 마스터 통합
- **JaCoCo**: 야간 풀빌드에서 cumulative coverage

## 비용 추정 (이전 답변에서 재인용)

| 항목 | 추정 |
|---|---|
| 초기 풀빌드 | 6-12시간 |
| 증분 빌드 (PR) | 5-30분 |
| 그래프 크기 | 노드 수천만, edge 수억 |
| 1쿼리 응답 시간 (산출물 생성) | 수초~수십초 |
| 엔지니어링 투입 | 시니어 2-3명 × 6-9개월 (PoC → production) |

## 운영 메트릭

- 인덱싱 성공률 (PR마다 정상 완료 %)
- 표류 (야간 풀빌드와 증분의 노드/엣지 차이)
- 생성 테스트 통과율 (현 phase별 정확도 추적)
- Neo4j 쿼리 p95 latency
- 그래프 메모리 사용량

## 점진 이식 단계

1. **Phase 6a (PoC)**: 단일 모듈, file-system archive 그대로
   — 진행됨 (2026-06-11): 파티션 샤드 스토어(`PartitionedGraphStore`) + 증분 빌드
   (`--incremental-base`/`--changed-files`). roadmap 6.1·6.2, `progress/6-1.md`·`6-2.md`
2. **Phase 6b (Neo4j 도입)**: 단일 모듈, Neo4j로 마이그레이션
   — 복귀 조건은 `decisions/graph-store-phase6.md`
3. **Phase 6c (멀티 모듈)**: 2-3개 핵심 모듈 분산 인덱싱
4. **Phase 6d (전체)**: 모든 모듈 + 야간 풀 + PR 증분
5. **Phase 6e (운영)**: 모니터링 + alerting + DR

각 단계 끝에 phase별 PoC 통과율 메트릭 측정.
