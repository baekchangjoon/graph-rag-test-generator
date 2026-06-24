package io.graphrag.builder.oracle;

import io.graphrag.builder.index.SourceRoots;

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

    /**
     * roots(전 소스 루트 파싱용)와 bootJar(바이트코드 분석용)를 담은 SUT 핸들.
     * srcDir() 접근자는 roots.primary()를 반환해 기존 소비처(ConcolicOracle 등) 호환을 유지한다.
     */
    record SutCode(SourceRoots roots, Path bootJar) {
        /** 후방호환 Path 접근자 — roots.primary() 반환. */
        public Path srcDir() {
            return roots.primary();
        }

        /** 보조 생성자 — 단일 루트 Path를 SourceRoots.single 으로 래핑한다. */
        public SutCode(Path srcDir, Path bootJar) {
            this(SourceRoots.single(srcDir), bootJar);
        }
    }

    InputCandidates analyze(SutCode sut);
}
