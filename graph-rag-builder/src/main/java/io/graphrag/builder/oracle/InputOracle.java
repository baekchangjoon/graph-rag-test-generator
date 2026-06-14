package io.graphrag.builder.oracle;

import java.nio.file.Path;

/**
 * 입력 발견 도구의 교체가능 인터페이스. SUT 코드를 분석해 분기를 여는 입력 후보값을 낸다.
 * out-of-process 빌더가 이 후보를 HTTP replay로 확정·관측해 그래프를 그린다.
 *
 * <p>구현 교체 가능: 정적 리터럴 추출(Spoon), concolic(ASM+Z3), 향후 EvoSuite/심볼릭 등.
 * 어떤 구현이든 산출물은 {@link InputCandidates}(필드별 후보)로 통일된다.
 */
public interface InputOracle {

    String name();

    /** srcDir(소스 분석용)와 bootJar(바이트코드 분석용)를 담은 SUT 핸들. */
    record SutCode(Path srcDir, Path bootJar) {
    }

    InputCandidates analyze(SutCode sut);
}
