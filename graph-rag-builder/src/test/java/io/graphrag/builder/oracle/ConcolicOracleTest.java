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
    }
}
