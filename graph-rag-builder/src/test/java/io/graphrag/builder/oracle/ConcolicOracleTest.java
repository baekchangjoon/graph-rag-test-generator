package io.graphrag.builder.oracle;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConcolicOracleTest {

    private static byte[] classBytes(String resource) throws Exception {
        try (InputStream in = ConcolicOracleTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void solvesDerivedLinearEquality() throws Exception {
        byte[] bytes = classBytes("io/graphrag/builder/oracle/fixtures/Derived.class");
        InputCandidates c = new ConcolicOracle().analyzeClassBytes(bytes);

        // score*2 == 84 → 42 (소스에 없는 값을 Z3가 도출)
        assertThat(c.numeric().get("score")).contains(42L);
        // amount+5 > 100 → 경계 96 부근
        assertThat(c.numeric().get("amount")).contains(96L);
        // long 파생: bonus*2 == 10000000000 → 5000000000 (int 범위 밖)
        assertThat(c.numeric().get("bonus")).contains(5000000000L);
        // 문자열 길이: code.length()==5 → 길이5 문자열
        assertThat(c.strings().get("code")).contains("xxxxx");
        // 단일필드 경로는 튜플을 내지 않는다 (무회귀)
        assertThat(c.tuples()).isEmpty();
    }

    @Test
    void solvesTwoFieldLinearTuple() throws Exception {
        byte[] bytes = classBytes("io/graphrag/builder/oracle/fixtures/InterFieldFixture.class");
        InputCandidates c = new ConcolicOracle().analyzeClassBytes(bytes);

        // gate: loyaltyPoints == nights*17 → 두 필드를 동시충족하는 튜플(예: 17,1). 필드별 후보론 불가능.
        assertThat(c.tuples()).anyMatch(t ->
                t.containsKey("loyaltyPoints") && t.containsKey("nights")
                        && t.get("loyaltyPoints").equals(t.get("nights") * 17));
    }

    @Test
    void bailsOnThreeFieldsAndProducts() throws Exception {
        byte[] bytes = classBytes("io/graphrag/builder/oracle/fixtures/InterFieldFixture.class");
        InputCandidates c = new ConcolicOracle().analyzeClassBytes(bytes);

        // 3-필드 선형(cap 2 초과)과 진짜 곱 a*b(비선형)은 튜플을 내지 않는다.
        assertThat(c.tuples()).noneMatch(t -> t.keySet().containsAll(java.util.Set.of("a", "b", "c")));
        assertThat(c.tuples()).noneMatch(t -> t.keySet().equals(java.util.Set.of("a", "b")));
    }
}
