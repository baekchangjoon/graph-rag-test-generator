package io.graphrag.builder.oracle;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** float/double inter-field Real solve 경로(작업 #4). 정수 경로 무회귀는 ConcolicOracleTest가 가드. */
class ConcolicFloatTest {

    private static byte[] classBytes(String resource) throws Exception {
        try (InputStream in = ConcolicFloatTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return in.readAllBytes();
        }
    }

    private static InputCandidates analyze() throws Exception {
        return new ConcolicOracle().analyzeClassBytes(
                classBytes("io/graphrag/builder/oracle/fixtures/FloatFixture.class"));
    }

    @Test
    void singleFloatFieldBoundaryProducesRealCandidate() throws Exception {
        InputCandidates c = analyze();
        // price >= 100.0f → 경계 100 부근 real 후보. reals 채널(Long numeric 아님).
        assertThat(c.reals().get("price")).anyMatch(v -> Math.abs(v - 100.0) <= 1.0);
    }

    @Test
    void singleDoubleFieldBoundaryProducesRealCandidate() throws Exception {
        InputCandidates c = analyze();
        // rate >= 1.5 (DCMP 경로) → 경계 1.5 부근 real 후보.
        assertThat(c.reals().get("rate")).anyMatch(v -> Math.abs(v - 1.5) <= 0.5);
    }

    @Test
    void twoFloatFieldLinearProducesRealTupleInBand() throws Exception {
        InputCandidates c = analyze();
        // base*2 + surcharge*3 가 band[99.5,100.5] → Real solveTuple이 동시충족 (base,surcharge)를 도출.
        assertThat(c.realTuples()).anyMatch(t ->
                t.containsKey("base") && t.containsKey("surcharge")
                        && inBand(t.get("base"), t.get("surcharge")));
    }

    @Test
    void floatAccessorPathProducesRealTuple() throws Exception {
        InputCandidates c = analyze();
        // 객체 파라미터 + float 접근자(q.base()/q.surcharge()) 경로도 동일한 real 튜플을 낸다(E2E와 동형).
        assertThat(c.realTuples()).anyMatch(t ->
                t.containsKey("base") && t.containsKey("surcharge")
                        && inBand(t.get("base"), t.get("surcharge")));
    }

    @Test
    void variableTimesVariableBailsNoTuple() throws Exception {
        InputCandidates c = analyze();
        // a*b (변수×변수) 비선형 → 어떤 real 튜플도 (a,b)를 내지 않는다.
        assertThat(c.realTuples()).noneMatch(t -> t.keySet().equals(java.util.Set.of("a", "b")));
    }

    @Test
    void intFloatMixedComparisonBails() throws Exception {
        InputCandidates c = analyze();
        // qty(int) + price(float) 혼합 → origin MIXED → 비교 자체를 record 안 함(정수/real 채널 모두 미출현).
        assertThat(c.realTuples()).noneMatch(t -> t.containsKey("qty"));
        assertThat(c.tuples()).noneMatch(t -> t.containsKey("qty"));
        assertThat(c.reals()).doesNotContainKey("qty");
        assertThat(c.numeric()).doesNotContainKey("qty");
    }

    /** SUT와 동일한 float 산술로 band를 판정 — solver 해가 경계에 붙으면 반올림으로 탈락(E2E 충실 재현). */
    private static boolean inBand(double base, double surcharge) {
        float combined = (float) base * 2.0f + (float) surcharge * 3.0f;
        return combined >= 99.5f && combined <= 100.5f;
    }
}
