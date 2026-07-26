package io.graphrag.builder.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-031: 삼중 저장 레이아웃({@code <root>/<endpointId>/{cand-NN|base/cand-NN|promoted/cand-NN|
 * failed/cand-NN}})·순번 증번·이동. 로더({@link TripleStore#candidates(String)})는 base/promoted/
 * failed를 제외한 top-level 대기 후보만 순번순으로 반환한다.
 */
class TripleStoreLayoutIT {

    @TempDir
    Path root;

    @Test
    @DisplayName("REQ-031: candidates()는 top-level cand-NN만 순번순으로 반환(base/promoted/failed 제외)")
    void candidatesListsOnlyTopLevelCandDirsInOrder() throws IOException {
        Path endpointDir = root.resolve("ep-1");
        Files.createDirectories(endpointDir.resolve("cand-02"));
        Files.createDirectories(endpointDir.resolve("cand-01"));
        Files.createDirectories(endpointDir.resolve("base").resolve("cand-01"));
        Files.createDirectories(endpointDir.resolve("promoted").resolve("cand-99"));
        Files.createDirectories(endpointDir.resolve("failed").resolve("cand-50"));

        TripleStore store = new TripleStore(root);
        List<Path> candidates = store.candidates("ep-1");

        assertThat(candidates).extracting(p -> p.getFileName().toString())
                .containsExactly("cand-01", "cand-02");
    }

    @Test
    @DisplayName("REQ-031: 존재하지 않는 endpointId는 빈 목록을 반환")
    void candidatesReturnsEmptyForUnknownEndpoint() throws IOException {
        TripleStore store = new TripleStore(root);
        assertThat(store.candidates("no-such-endpoint")).isEmpty();
    }

    @Test
    @DisplayName("REQ-031: promote는 후보를 promoted/cand-NN(원 순번 보존)으로 이동하고 원본은 사라진다")
    void promoteMovesCandidateAndRemovesOriginal() throws IOException {
        Path candDir = createCandidate(root, "ep-2", "cand-01");

        TripleStore store = new TripleStore(root);
        Path promoted = store.promote(candDir);

        assertThat(promoted).isEqualTo(root.resolve("ep-2").resolve("promoted").resolve("cand-01"));
        assertThat(Files.exists(promoted.resolve("body.json"))).isTrue();
        assertThat(Files.exists(promoted.resolve("seed.sql"))).isTrue();
        assertThat(Files.exists(promoted.resolve("stubs.json"))).isTrue();
        assertThat(Files.exists(candDir)).isFalse();
        // base/ 사본은 재기록/이동되지 않고 그대로 남는다(마커-diff 기준선 보존).
        assertThat(Files.isDirectory(root.resolve("ep-2").resolve("base").resolve("cand-01"))).isTrue();
    }

    @Test
    @DisplayName("REQ-031: promoted/cand-01 기존재 시 신규 승격은 cand-02로 자동 증번(기존 디렉토리 보존, 덮어쓰기 없음)")
    void promoteAutoIncrementsOnSeqConflictWithoutOverwriting() throws IOException {
        Path endpointDir = root.resolve("ep-3");
        Path existingPromoted = Files.createDirectories(endpointDir.resolve("promoted").resolve("cand-01"));
        Files.writeString(existingPromoted.resolve("body.json"), "{\"existing\":true}");

        Path candDir = createCandidate(root, "ep-3", "cand-01");

        TripleStore store = new TripleStore(root);
        Path promoted = store.promote(candDir);

        assertThat(promoted).isEqualTo(endpointDir.resolve("promoted").resolve("cand-02"));
        assertThat(Files.readString(existingPromoted.resolve("body.json"))).contains("existing");
        assertThat(Files.exists(candDir)).isFalse();
    }

    @Test
    @DisplayName("REQ-031: fail은 후보를 failed/cand-NN으로 이동하고 digest.txt에 실패 사유를 기록한다")
    void failMovesCandidateAndRecordsDigest() throws IOException {
        Path candDir = createCandidate(root, "ep-4", "cand-01");

        TripleStore store = new TripleStore(root);
        Path failed = store.fail(candDir, "reject: schema violation at note");

        assertThat(failed).isEqualTo(root.resolve("ep-4").resolve("failed").resolve("cand-01"));
        assertThat(Files.exists(candDir)).isFalse();
        assertThat(Files.readString(failed.resolve("digest.txt"))).contains("schema violation");
    }

    @Test
    @DisplayName("REQ-031: failed/cand-01 기존재 시 신규 실패도 cand-02로 자동 증번")
    void failAutoIncrementsOnSeqConflict() throws IOException {
        Path endpointDir = root.resolve("ep-4b");
        Files.createDirectories(endpointDir.resolve("failed").resolve("cand-01"));
        Path candDir = createCandidate(root, "ep-4b", "cand-01");

        TripleStore store = new TripleStore(root);
        Path failed = store.fail(candDir, "digest");

        assertThat(failed).isEqualTo(endpointDir.resolve("failed").resolve("cand-02"));
    }

    @Test
    @DisplayName("REQ-031/009: base/ 사본이 없으면 promote는 reject하고 원본을 이동하지 않는다(검증 불가 상태 accept 금지)")
    void promoteRejectsWhenBaseMissing() throws IOException {
        Path endpointDir = root.resolve("ep-5");
        Path candDir = Files.createDirectories(endpointDir.resolve("cand-01"));
        Files.writeString(candDir.resolve("body.json"), "{}");
        Files.writeString(candDir.resolve("seed.sql"), "");
        Files.writeString(candDir.resolve("stubs.json"), "{}");
        // base/cand-01 없음(누락)

        TripleStore store = new TripleStore(root);
        assertThatThrownBy(() -> store.promote(candDir)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(candDir)).isTrue();
        assertThat(Files.isDirectory(endpointDir.resolve("promoted"))).isFalse();
    }

    @Test
    @DisplayName("REQ-031/009: base/와 candidate 파일 구성이 다르면 promote는 reject한다")
    void promoteRejectsWhenBaseFileSetMismatches() throws IOException {
        Path endpointDir = root.resolve("ep-6");
        Path candDir = Files.createDirectories(endpointDir.resolve("cand-01"));
        Files.writeString(candDir.resolve("body.json"), "{}");
        Files.writeString(candDir.resolve("seed.sql"), "");
        Files.writeString(candDir.resolve("stubs.json"), "{}");
        Path baseDir = Files.createDirectories(endpointDir.resolve("base").resolve("cand-01"));
        Files.writeString(baseDir.resolve("body.json"), "{}");
        // baseDir에는 seed.sql/stubs.json이 없음 — 파일 구성 불일치

        TripleStore store = new TripleStore(root);
        assertThatThrownBy(() -> store.promote(candDir)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(candDir)).isTrue();
    }

    @Test
    @DisplayName("REQ-031: candDir이 root 바로 아래 <endpointId>/cand-NN이 아니면(경로 오용) promote/fail은 IllegalArgumentException으로 거부")
    void promoteAndFailRejectPathsOutsideExpectedLayout() throws IOException {
        Path candDir = createCandidate(root, "ep-7", "cand-01");
        // base/cand-01은 CAND_DIR 정규식("cand-NN")은 통과하지만 root/<endpointId>/cand-NN보다
        // 한 단계 더 깊다 — 실수로 base/promoted/failed 경로를 넘기는 오용을 재현한다.
        Path misusedPath = candDir.getParent().resolve("base").resolve("cand-01");

        TripleStore store = new TripleStore(root);
        assertThatThrownBy(() -> store.promote(misusedPath)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.fail(misusedPath, "digest")).isInstanceOf(IllegalArgumentException.class);
        // 거부됐으므로 아무 것도 이동하지 않는다 — 정상 candDir/misusedPath 모두 그대로.
        assertThat(Files.exists(candDir)).isTrue();
        assertThat(Files.exists(misusedPath)).isTrue();
    }

    private static Path createCandidate(Path root, String endpointId, String candName) throws IOException {
        Path endpointDir = root.resolve(endpointId);
        Path candDir = Files.createDirectories(endpointDir.resolve(candName));
        Path baseDir = Files.createDirectories(endpointDir.resolve("base").resolve(candName));
        for (Path dir : List.of(candDir, baseDir)) {
            Files.writeString(dir.resolve("body.json"), "{}");
            Files.writeString(dir.resolve("seed.sql"), "");
            Files.writeString(dir.resolve("stubs.json"), "{}");
        }
        Files.writeString(candDir.resolve("notes.md"), "notes");
        return candDir;
    }
}
