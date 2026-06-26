# graph.json 실시간 점진적 추가/업데이트 방식 설계 검토 (후속 작업)

> 상태: **추후 진행용 설계 노트(follow-up)**. 구현 착수 전 단계.
> 이 문서는 "graph-rag-builder 실행 시 분석 결과(graph.json)를 마지막에 한 번에 저장하지 않고, 
> path나 endpoint가 탐색 완료될 때마다 실시간으로 추가/업데이트하는 방식"의 타당성, 장단점 및 
> 대안 아키텍처를 검토하여 추후 구현에 참고할 수 있도록 정리한 것이다.

작성일: 2026-06-26

---

## 1. 개요 및 현재 구조

현재 `graph-rag-builder`의 탐색 산출물인 `graph.json`(`GraphAsset`)은 SUT(System Under Test) 동적 탐색 및 정적 분석이 완료되는 마지막 시점에 메모리 상의 데이터를 [GraphStore](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/store/GraphStore.java) 인터페이스를 통해 한 번에 파일로 저장(Serialize)한다.

Phase 6에서 도입된 [PartitionedGraphStore](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/store/PartitionedGraphStore.java)는 이를 세분화하여 `global.json`과 개별 핸들러 클래스 단위의 `partitions/*.json` 파일로 나누어 저장하고, 이를 통해 증분 빌드([IncrementalBuildPlanner](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/cli/IncrementalBuildPlanner.java)) 시 변경된 파일의 파티션만 갱신하는 최적화를 구현했다.

여기에 더해 **"실시간 탐색 완료 시점마다 파일에 점진적으로 추가(Append/Update)하는 방식"**에 대한 기술적 평가를 아래와 같이 정리한다.

---

## 2. 실시간 점진적 추가 방식의 타당성 평가

### 🟢 장점 (Pros)
1. **장시간 탐색 시 복구 회복력 (Resilience & Checkpointing)**:
   - SUT를 외부 프로세스로 기동하여 수십 분간 동적 탐색을 실행할 때, 예기치 않은 시스템 종료(OOM, Timeout, DB 연결 단절 등)가 발생해도 **이미 탐색 완료된 path/endpoint는 파일에 즉시 영속화**된다.
   - 분석 중단 시 처음부터 다시 실행하지 않고, 실패한 시점 이후의 path부터 이어서 탐색(Resume)할 수 있는 인프라가 마련된다.
2. **메모리 풋프린트 절감 (Memory Footprint)**:
   - 수백 개의 API 엔드포인트와 수천 개의 실행 분기를 분석하는 초대형 프로젝트에서 모든 지문과 캡처된 facts(SQL, Mock, Seed)를 힙 메모리에 유지할 필요 없이, 탐색 완료 직후 디스크로 플러시(Flush)하여 GC 부하를 크게 낮울 수 있다.

### 🔴 단점 및 설계 과제 (Cons & Challenges)
1. **데이터 참조 정합성 (Graph Integrity) 유지가 어려움**:
   - `GraphAsset`은 단순한 평면 데이터 리스트가 아니라, `ExploredPath` -> `CapturedSql` / `CapturedHttpCall` / `RequiredSeed` 등이 ID를 기반으로 촘촘히 얽혀 있는 directed graph이다.
   - 단일 노드나 엣지 단위로 파일에 개별 쓰기를 반복하면, 도중에 프로세스가 종료되었을 때 상호 참조가 깨진 깨진 그래프(Dangling Reference)가 남을 위험이 매우 높다.
2. **디스크 I/O 오버헤드로 인한 탐색 속도 지연**:
   - 동적 탐색 루프 내에서 분기(probe)를 거칠 때마다 파일 전체 혹은 특정 파티션을 실시간으로 갱신하면 파일 락(Locking) 경합과 디스크 I/O 병목이 발생하여 SUT 탐색 전체 속도가 급격히 저하될 수 있다.

---

## 3. 추천 대안: 컨트롤러/단계 단위 배치 플러시 (Flush-on-Controller)

실시간 무조건 추가 방식의 I/O 병목과 데이터 깨짐 리스크를 극복하면서 회복력을 챙기기 위해 다음과 같은 **절충안(Checkpointing)** 설계를 제안한다.

* **동작 단계(Stage) 완료 시 플러시**:
  - `graph-rag-builder`의 탐색 과정에서 주요 이정표(Milestone) 또는 입력 발견 단계(Stage 0 → 1 → 2 → 3 → 3b)가 완료될 때마다 중간 수집된 그래프 스냅샷을 디스크의 `.graph.json.tmp` 파일로 원자적(Atomic)으로 덮어쓰고, 최종 성공 시 rename 처리한다.
* **파티션 단위 쓰기 트랜잭션 적용**:
  - [PartitionedGraphStore](file:///root/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/store/PartitionedGraphStore.java)의 구조를 적극 활용하여, **특정 Controller/Handler 클래스의 모든 path 탐색이 완전히 끝나는 시점**에 해당 핸들러의 파티션 파일(`partitions/<ControllerName>.json`)만 디스크에 Write 트랜잭션 단위로 flush한다.
  - 이렇게 하면 매 path마다 디스크를 쓰는 I/O 비용을 획기적으로 줄이면서도 클래스 단위의 정합성과 실시간 체크포인트를 동시에 확보할 수 있다.
