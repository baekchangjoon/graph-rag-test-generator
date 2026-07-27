package io.graphrag.builder.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-036 저장 CLI 계약: {@code --triple-store}(생성/소비 루트)와 {@code --triple-candidates}
 * (trial이 읽는 대기 후보 디렉토리)의 해석 규칙이 {@code build}/{@code synthesize-triple}/{@code trial}
 * 세 서브커맨드에서 동일하게 적용되는지 검증한다.
 *
 * <p>해석 함수({@link BuilderCli#tripleStoreRoot}/{@link BuilderCli#tripleCandidatesRoot})를 직접
 * 검증하는 순수 단위 테스트다 — 기본 경로가 상대 경로({@code .graphrag/triples})라 CLI를 실제로
 * 실행하면 작업 디렉토리(=모듈 루트)에 디렉토리를 만들어 저장소를 오염시킨다. 세 서브커맨드가 모두
 * 이 함수만 쓴다는 사실은 {@code BuilderCli} 소스의 단일 호출 지점으로 보장된다.
 */
class TripleStoreCliContractTest {

    @Test
    @DisplayName("REQ-036: --triple-store/--triple-candidates 미지정 시 기본 경로(.graphrag/triples)가 적용된다")
    void req036_bothFlagsAbsentFallBackToDefaultTripleStorePath() {
        Map<String, String> noFlags = Map.of();

        assertThat(BuilderCli.tripleStoreRoot(noFlags))
                .as("생성/소비 루트는 미지정 시 SUT 캠페인 관례 경로여야 한다")
                .isEqualTo(Path.of(".graphrag/triples"));
        assertThat(BuilderCli.tripleCandidatesRoot(noFlags))
                .as("대기 후보 디렉토리도 미지정 시 같은 기본 경로로 수렴해야 한다"
                        + "(후보 루트를 따로 두고 싶을 때만 --triple-candidates를 쓴다)")
                .isEqualTo(Path.of(".graphrag/triples"));
        assertThat(BuilderCli.DEFAULT_TRIPLE_STORE)
                .as("기본 경로 상수는 요구사항명세 문면과 동일해야 한다")
                .isEqualTo(".graphrag/triples");
    }

    @Test
    @DisplayName("REQ-036: --triple-store만 주면 후보 디렉토리도 그 루트를 따른다")
    void req036_tripleStoreAloneAlsoDrivesCandidatesRoot() {
        Map<String, String> options = Map.of("--triple-store", "e2e/triples");

        assertThat(BuilderCli.tripleStoreRoot(options)).isEqualTo(Path.of("e2e/triples"));
        assertThat(BuilderCli.tripleCandidatesRoot(options))
                .as("e2e 스크립트가 커밋된 fixture 경로를 --triple-store 하나로 전달하면 "
                        + "그 경로가 대기 후보(promoted 포함) 소비 루트가 되어야 한다(REQ-036 수용기준 2)")
                .isEqualTo(Path.of("e2e/triples"));
    }

    @Test
    @DisplayName("REQ-036: --triple-candidates는 후보 디렉토리만 덮어쓰고 저장 루트는 --triple-store를 유지한다")
    void req036_tripleCandidatesOverridesOnlyCandidatesRoot() {
        Map<String, String> options = Map.of(
                "--triple-store", "build/triples",
                "--triple-candidates", "e2e/triples");

        assertThat(BuilderCli.tripleStoreRoot(options))
                .as("생성 루트는 --triple-store 그대로여야 한다")
                .isEqualTo(Path.of("build/triples"));
        assertThat(BuilderCli.tripleCandidatesRoot(options))
                .as("두 플래그가 함께 오면 후보 루트만 --triple-candidates로 갈라진다")
                .isEqualTo(Path.of("e2e/triples"));
    }

    @Test
    @DisplayName("REQ-036: --triple-candidates만 줘도(--triple-store 없이) 그 경로가 후보 루트가 된다(기존 호출부 호환)")
    void req036_tripleCandidatesAloneStillWorksForBackwardCompatibility() {
        Map<String, String> options = Map.of("--triple-candidates", "e2e/triples");

        assertThat(BuilderCli.tripleCandidatesRoot(options)).isEqualTo(Path.of("e2e/triples"));
        assertThat(BuilderCli.tripleStoreRoot(options))
                .as("--triple-store를 주지 않았으므로 생성 루트는 기본 경로로 남는다")
                .isEqualTo(Path.of(".graphrag/triples"));
    }
}
