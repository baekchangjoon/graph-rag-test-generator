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
 * {@code promoted}/{@code failed} 버킷으로 이동한다. CLI 계약(`--triple-store`/`--triple-candidates`
 * 플래그, e2e fixture 경로)은 REQ-036(후속 task) 소관이며 이 클래스는 레이아웃/이동만 다룬다.
 *
 * <p><b>순번 정책:</b> 이동 시 원 순번을 우선 보존한다(예: {@code cand-01} → {@code promoted/cand-01}).
 * 대상 버킷에 같은 순번이 이미 있으면(예: fixture로 미리 심어둔 {@code promoted/cand-01}) 절대
 * 덮어쓰지 않고, 그 버킷의 최대 순번+1로 자동 증번한다.
 *
 * <p><b>단일 writer 전제(비원자적 증번):</b> {@link #move}의 증번 로직은 "대상 순번 존재 확인 →
 * 최대 순번 계산 → move"가 원자적이지 않다(TOCTOU) — 동일 {@code endpointId}에 대해 두 호출자가
 * 동시에 {@code promote}/{@code fail}을 호출하면 같은 순번을 계산해 경쟁할 수 있다. 이 클래스는
 * <b>endpointId별 단일 writer</b>를 전제하며, 동시 promote/fail 안전성은 REQ-017(trial 직렬화 —
 * endpoint 단위로 trial 시드/invoke 구간을 병렬 탐색과 겹치지 않게 직렬 실행)이 상위 계층에서
 * 보장하기 전까지 이 클래스 자체는 지원하지 않는다. REQ-017이 구현되기 전에는 호출자가 endpoint
 * 단위 직렬 호출을 책임져야 한다.
 *
 * <p><b>base/ 무결성(Task 9~10 인계):</b> base/ 사본은 마커-diff({@link TripleValidator})의 불변
 * 기준선이므로 이 클래스는 base/를 절대 재기록하지 않는다({@code candidates}는 읽기 전용 후보 목록만
 * 반환) — {@link #baseDirFor(Path)}로 규약 상 위치만 계산해 넘긴다. {@link #promote(Path)}는 승격
 * 직전 base/ 사본이 존재하고 candidate와 파일 구성(body.json/seed.sql/stubs.json)이 같은 집합인지
 * 확인하고, 아니면(검증 불가 상태) 승격을 거부한다(reject) — 이동은 일어나지 않는다. 이 검사는
 * "파일 구성(존재 여부)"만 전제로 확인하는 얕은 게이트다 — 값/마커 내용 대조(REQ-009 마커-diff)는
 * {@link TripleValidator#validate}의 몫이며, 이 클래스가 대신하지 않는다.
 *
 * <p><b>candDir 경로 전제:</b> {@link #promote(Path)}/{@link #fail(Path, String)}에 넘기는
 * {@code candDir}은 반드시 이 store의 {@code root} 바로 아래 {@code <endpointId>/cand-NN} 형태여야
 * 한다(예: {@link #candidates(String)}가 반환한 경로). {@code base/cand-01}처럼 한 단계 더 깊거나
 * root 밖의 경로를 넘기면(오용) 방어적으로 {@link IllegalArgumentException}을 던진다.
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
     * 안전망 — 마커-diff 전제가 깨진 후보를 승격 상태로 accept하지 않는다). candDir이 이 store의
     * {@code root} 바로 아래 {@code <endpointId>/cand-NN} 형태가 아니면(경로 오용)
     * {@link IllegalArgumentException}을 던진다. <b>동일 endpointId에 대한 동시 호출은 지원하지
     * 않는다</b>(클래스 Javadoc "단일 writer 전제" 참고 — REQ-017 전까지 호출자가 직렬화해야 한다).
     */
    public Path promote(Path candDir) throws IOException {
        assertWithinRoot(candDir);
        assertBaseConsistent(candDir);
        return move(candDir, "promoted");
    }

    /**
     * candDir을 {@code failed/cand-NN}으로 이동하고, 그 디렉토리에 {@code digest.txt}로 실패 사유를
     * 기록한다. candDir 경로 전제·동시성 전제는 {@link #promote(Path)}와 동일하다.
     */
    public Path fail(Path candDir, String digest) throws IOException {
        assertWithinRoot(candDir);
        Path moved = move(candDir, "failed");
        Files.writeString(moved.resolve("digest.txt"), digest == null ? "" : digest);
        return moved;
    }

    /**
     * candDir이 {@code root/<endpointId>/cand-NN} 형태인지만 확인하는 얕은 경로 전제 검사다(오용
     * 방어). base/promoted/failed 등 다른 버킷의 경로를 잘못 넘기는 실수를 조기에 막는다.
     */
    private void assertWithinRoot(Path candDir) {
        Path endpointDir = candDir.toAbsolutePath().normalize().getParent();
        Path expectedRoot = root.toAbsolutePath().normalize();
        if (endpointDir == null || !expectedRoot.equals(endpointDir.getParent())) {
            throw new IllegalArgumentException(
                    "candDir는 이 TripleStore의 root 바로 아래 <endpointId>/cand-NN 경로여야 함(전제 위반): "
                            + candDir + " (root=" + root + ")");
        }
    }

    /**
     * base/ 사본과 candidate 간 <b>파일 구성(존재 여부)</b>만 대조하는 얕은 전제 검사다 — 값/마커
     * 내용 대조(REQ-009 마커-diff)는 이 메서드의 책임이 아니며 {@link TripleValidator#validate}가
     * 승격 전 별도로 수행한다. 여기서는 마커-diff를 수행할 수 없는 상태(base 누락·파일 집합 불일치)를
     * 조기에 걸러 "검증 불가 상태를 승격으로 accept"하는 사고를 막는다.
     */
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

    /**
     * candDir을 bucket(promoted/failed) 디렉토리로 이동한다. 순번 충돌 확인(exists) → 최대 순번
     * 계산(nextAvailableSeq) → move가 원자적이지 않다(TOCTOU) — 클래스 Javadoc "단일 writer 전제"
     * 참고. 동일 endpointId·bucket에 대한 동시 호출은 이 메서드 밖(호출자)에서 직렬화해야 한다.
     */
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
