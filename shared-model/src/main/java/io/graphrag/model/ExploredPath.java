package io.graphrag.model;

import java.util.List;
import java.util.Objects;

/**
 * 분기 탐색 엔진이 발견한 코드 경로의 사실.
 *
 * <p>{@link #pathConstraint()}는 JDART 등 콘콜릭 엔진에서만 채워지고, FUZZER/EVOSUITE는 null 가능.
 *
 * @param id ULID 형식 식별자
 * @param branchesTaken 도달한 분기 id 시퀀스
 * @param exitStatus HTTP 응답 코드 (또는 5xx)
 * @param exitResponseShape 캡처된 응답 body 구조. nullable.
 * @param coverageSignature JaCoCo coverage 해시 (중복 path 식별)
 * @param codeVersion 분석 시점 commit SHA
 */
public record ExploredPath(
        String id,
        String endpointId,
        PathExplorerKind discoveredBy,
        SampleInput sampleInput,
        PathConstraint pathConstraint,
        List<String> branchesTaken,
        int exitStatus,
        Object exitResponseShape,
        String coverageSignature,
        String codeVersion) {

    public ExploredPath {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(discoveredBy, "discoveredBy");
        Objects.requireNonNull(sampleInput, "sampleInput");
        Objects.requireNonNull(coverageSignature, "coverageSignature");
        Objects.requireNonNull(codeVersion, "codeVersion");
        branchesTaken = List.copyOf(Objects.requireNonNullElse(branchesTaken, List.of()));
    }
}
