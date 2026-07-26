package io.graphrag.builder.provenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T1 삼중 저장 레이아웃(REQ-031): {@code <root>/<endpointId>/{cand-NN | base/cand-NN |
 * promoted/cand-NN | failed/cand-NN}}. {@link #candidates(String)}가 대기 중인(top-level) 후보를
 * 순번순으로 로드하고, {@link #promote(Path)}/{@link #fail(Path, String)}가 각각
 * {@code promoted}/{@code failed} 버킷으로 이동한다.
 *
 * <p><b>순번 정책:</b> 이동 시 원 순번을 우선 보존한다(예: {@code cand-01} → {@code promoted/cand-01}).
 * 대상 버킷에 같은 순번이 이미 있으면(예: fixture로 미리 심어둔 {@code promoted/cand-01}) 절대
 * 덮어쓰지 않고, 그 버킷의 최대 순번+1로 자동 증번한다.
 *
 * <p><b>base/ 무결성(Task 9~10 인계):</b> base/ 사본은 마커-diff({@link TripleValidator})의 불변
 * 기준선이므로 이 클래스는 base/를 절대 재기록하지 않는다({@code candidates}는 읽기 전용 후보 목록만
 * 반환) — {@link #baseDirFor(Path)}로 규약 상 위치만 계산해 넘긴다. {@link #promote(Path)}는 승격
 * 직전 base/ 사본이 존재하고 candidate와 파일 구성(body.json/seed.sql/stubs.json)이 같은 집합인지
 * 확인하고, 아니면(검증 불가 상태) 승격을 거부한다(reject) — 이동은 일어나지 않는다.
 */
public final class TripleStore {

    /** promote 전 base/ 파일 구성과 비교하는 대상 — notes.md는 candidate 전용(REQ-009)이라 제외. */
    private static final List<String> COMPARED_FILES = List.of("body.json", "seed.sql", "stubs.json");

    private static final Pattern CAND_DIR = Pattern.compile("cand-(\\d+)");

    private final Path root;

    public TripleStore(Path root) {
        this.root = root;
    }

    /** endpointId 아래 대기 중인(top-level) cand-NN 디렉토리를 순번 오름차순으로 반환. base/promoted/failed는 제외. */
    public List<Path> candidates(String endpointId) throws IOException {
        Path endpointDir = root.resolve(endpointId);
        if (!Files.isDirectory(endpointDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(endpointDir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> CAND_DIR.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(TripleStore::seqOf))
                    .toList();
        }
    }

    /** candDir(예: {@code <root>/<ep>/cand-01})과 동일 규약의 {@code base/cand-01} 경로(존재 확인은 하지 않음). */
    public Path baseDirFor(Path candDir) {
        return candDir.getParent().resolve("base").resolve(candDir.getFileName());
    }

    /**
     * candDir을 {@code promoted/cand-NN}으로 이동한다. base/ 사본이 없거나 파일 구성이 candidate와
     * 다르면(검증 불가 상태) {@link IllegalStateException}을 던지고 이동하지 않는다(REQ-009/031
     * 안전망 — 마커-diff 전제가 깨진 후보를 승격 상태로 accept하지 않는다).
     */
    public Path promote(Path candDir) throws IOException {
        assertBaseConsistent(candDir);
        return move(candDir, "promoted");
    }

    /** candDir을 {@code failed/cand-NN}으로 이동하고, 그 디렉토리에 {@code digest.txt}로 실패 사유를 기록한다. */
    public Path fail(Path candDir, String digest) throws IOException {
        Path moved = move(candDir, "failed");
        Files.writeString(moved.resolve("digest.txt"), digest == null ? "" : digest);
        return moved;
    }

    private void assertBaseConsistent(Path candDir) throws IOException {
        Path baseDir = baseDirFor(candDir);
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalStateException(
                    "승격 거부(REQ-009/031, base/ 사본 없음 — 검증 불가 상태): " + baseDir);
        }
        for (String fileName : COMPARED_FILES) {
            boolean inBase = Files.exists(baseDir.resolve(fileName));
            boolean inCand = Files.exists(candDir.resolve(fileName));
            if (inBase != inCand) {
                throw new IllegalStateException(
                        "승격 거부(REQ-009/031, base/-candidate 파일 구성 불일치): " + fileName
                                + " base=" + inBase + " candidate=" + inCand);
            }
        }
    }

    private Path move(Path candDir, String bucket) throws IOException {
        Path endpointDir = candDir.getParent();
        Path bucketDir = Files.createDirectories(endpointDir.resolve(bucket));
        int sourceSeq = seqOf(candDir);
        String preferredName = candName(sourceSeq);
        int seq = Files.exists(bucketDir.resolve(preferredName))
                ? nextAvailableSeq(bucketDir)
                : sourceSeq;
        Path dest = bucketDir.resolve(candName(seq));
        Files.move(candDir, dest);
        return dest;
    }

    /** bucketDir 안의 기존 cand-NN 중 최대 순번 + 1 (없으면 1). */
    private static int nextAvailableSeq(Path bucketDir) throws IOException {
        int max = 0;
        try (Stream<Path> entries = Files.list(bucketDir)) {
            List<Path> all = entries.toList();
            for (Path p : all) {
                Matcher m = CAND_DIR.matcher(p.getFileName().toString());
                if (m.matches()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        }
        return max + 1;
    }

    private static int seqOf(Path candDir) {
        Matcher m = CAND_DIR.matcher(candDir.getFileName().toString());
        if (!m.matches()) {
            throw new IllegalArgumentException("cand-NN 형식이 아닌 디렉토리: " + candDir);
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("순번 파싱 실패: " + candDir, e);
        }
    }

    private static String candName(int seq) {
        return String.format("cand-%02d", seq);
    }

    /** 테스트/디버그 편의 — 현재 root. */
    public Path root() {
        return root;
    }
}
