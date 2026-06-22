# Task 9 Report — PoC 종합 판정 (REQ-008) + suite 정리

작성일: 2026-06-23

---

## 종합 판정 (REQ-008)

**전략 A(pjacoco 단일 SUT fan-out)는 아키텍처적으로 실현 가능하다.**

| 게이트 | REQ | 결과 | 핵심 수치 |
|---|---|---|---|
| V1 공존 부팅 | REQ-001 | ✅ PASS | lines=253, port6300=closed |
| V2 교차오염 | REQ-002 | ✅ PASS | contamination=0, ownA=14, ownB=12 |
| V2-seeding | REQ-003 | ✅ PASS | workers=8, exceptions=0 |
| V3(a) partition 등가 | REQ-004 | ✅ PASS | `{{0,2},{1},{3}}` 일치, distinct-paths=3 |
| V3(b) 오버헤드 | REQ-005 | ⚠️ OVER-THRESHOLD | flush 4.1ms ✅, 벽시계 +15.80% ❌ — 재논의 |
| V4 단일 JVM | REQ-006 | ✅ PASS | diary 118 probes |
| V4 멀티 JVM | REQ-007 | ✅ PASS | mindgraph 72 probes / consumer 58 probes |
| A 종합 판정 | REQ-008 | ✅ PASS | §11 기록 완료 |
| pjacoco 주입 | REQ-009 | ✅ PASS | unit-green |

V3(b) 오버헤드는 §7(b) 성능 항목(재논의)으로 A 불가 트리거가 아님. 자동 B 회귀 없음.

---

## cleanup diffs

### 1. V3ArmEquivalencePoc.java — 폐기 테스트 @Disabled 처리

```diff
+import org.junit.jupiter.api.Disabled;
+
+    @Test
+    @Disabled("superseded by perRequestOtelScope_yieldsSamePartition — key equality intentionally rejected per design §5.1; baggage path drops pre-servlet filter probes")
     @DisplayName("REQ-004: per-request testId arm 등가 = vanilla coverageKey 집합과 일치 (V3(a) 게이트)")
     void perRequestTestId_yieldsSameCoverageKeySet() throws Exception {
```

`@Disabled` 이유: cross-vector KEY equality 비교는 §5.1 rev.4에서 partition 등가로 대체됨. 폐기 테스트는 왜 key-equality가 기각됐는지 문서화하므로 삭제하지 않고 disabled 유지.

### 2. v2-cross-contamination.sh — awk field $7→$9 (BRANCH_COVERED→LINE_COVERED)

```diff
-    awk -F, -v cls="$classname" 'NR>1 && $3==cls {print $0}' "$csv" | awk -F, '{print $7}' | head -1
+    # $9 = LINE_COVERED
+    awk -F, -v cls="$classname" 'NR>1 && $3==cls {print $0}' "$csv" | awk -F, '{print $9}' | head -1
```

JaCoCo CSV 컬럼: `GROUP(1),PACKAGE(2),CLASS(3),INSTRUCTION_MISSED(4),INSTRUCTION_COVERED(5),BRANCH_MISSED(6),BRANCH_COVERED(7),LINE_MISSED(8),LINE_COVERED(9),...`
`$7=BRANCH_COVERED`(잘못된 값)를 `$9=LINE_COVERED`(올바른 값)로 수정.

### 3. design spec §11 종합 판정 절 추가

`docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md` §11 끝에 "종합 판정 — 2026-06-23 (REQ-008)" 절 추가:
- 게이트별 최종 결과 표
- A viable 판정 + 열린 항목(V3b) 명시
- 자동 B 회귀 없음 선언
- 정직한 한계 3개 문서화

### 4. requirements matrix 갱신

`docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md`:
- REQ-008: 🔴 planned → 🟢 PASS (§11 기록 완료)
- REQ-009: 🟡 unit-green → 🟢 unit-green
- REQ-005: ⚠️ STILL-OVER (NOT green, 측정 완료 / 사용자 재논의 필요)
- Coverage 라인 갱신: 8/9 gates green; REQ-005 over-threshold (재논의)

---

## suite run 결과

**실행 방식**: 포트 8080 충돌을 피하기 위해 각 테스트 클래스를 개별 실행.
**JVM arg 전달**: `JAVA_OPTS="-Dpjacoco.agent.jar=$PJACOCO_JAR"` (Gradle JVM → build.gradle.kts systemProperty 경유 → test JVM).

| 클래스 | 통과 | 실패 | 스킵 | 비고 |
|---|---|---|---|---|
| V1AgentCoexistencePoc | 1 | 0 | 0 | ✅ |
| V2CrossContaminationPoc | 1 | 0 | 0 | ✅ |
| V2ConcurrentSeedingPoc | 1 | 0 | 0 | ✅ |
| V3ArmEquivalencePoc | 1 | 0 | 1 | ✅ 1 SKIPPED(@Disabled 폐기) |
| V3OtelScopeEquivalenceProbe | 1 | 0 | 0 | ✅ |
| PjacocoAgentTest | 2 | 0 | 0 | ✅ REQ-009 |
| V3OverheadPoc | 0 | 1 | 0 | ⚠️ REQ-005 초과(예상됨) |
| V3OverheadProductionPoc | 0 | 1 | 0 | ⚠️ REQ-005 초과(예상됨) |
| V4DistributedAttributionPoc | 0 | 2 | 0 | Docker compose 미기동(예상됨; commit 5721e3c 검증 완료) |
| **합계** | **8** | **4** | **1** | |

**실패 분류**:
- V3OverheadPoc(2): REQ-005 의도적 FAIL — 오버헤드가 임계 초과(+15.80%)이며 이는 PoC에서 측정·기록해야 하는 결과. Green으로 만들지 않음.
- V4DistributedAttributionPoc(2): Docker compose 미기동 환경 — V4는 commit `5721e3c`에서 diary 118 probes + mindgraph 72 probes로 이미 검증됨. 본 Task 9 재실행은 인프라 부재로 스킵.

**스킵 1건**: `perRequestTestId_yieldsSameCoverageKeySet` — `@Disabled` 정상 적용 확인(이전 FAIL → 이번 SKIPPED).
