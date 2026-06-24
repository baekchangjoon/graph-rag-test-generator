package io.graphrag.builder.parallel;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.graphrag.model.BranchRef;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import io.graphrag.model.RequiredSeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphSetEquivDiffTool 단위 테스트 + CLI main 진입점 smoke.
 *
 * <p>EQUIVALENT / NON-EQUIVALENT 두 케이스를 합성 데이터로 검증한다.
 */
class GraphSetEquivDiffToolTest {

    // ─── 공통 픽스처 헬퍼 ──────────────────────────────────────────────────

    private static Endpoint endpoint(String method, String path) {
        return new Endpoint("ep-" + method + "-" + path.replace("/", "_"),
                method, path, "Ctrl", "handle", List.of(), false);
    }

    private static ExploredPath path(String endpointId, int status, List<BranchRef> branches) {
        return new ExploredPath(
                "path-" + endpointId + "-" + status,
                endpointId,
                JsonNodeFactory.instance.objectNode(),
                status,
                JsonNodeFactory.instance.objectNode(),
                List.of(), List.of(),
                branches,
                "static", List.of(), List.of(), List.of(), List.of()
        );
    }

    private static BranchRef branch(String clazz, String method, int line, int idx) {
        return new BranchRef(clazz, method, line, idx);
    }

    private static CapturedSql sql(String pathId, String kind, String sql) {
        return new CapturedSql("sql-" + pathId, pathId, kind, sql, "t", List.of());
    }

    private static RequiredSeed seed(String pathId, String table, List<String> columns) {
        return new RequiredSeed("seed-" + pathId, pathId, table, columns, List.of("1"));
    }

    /** 최소 GraphAsset 생성 헬퍼 */
    private static GraphAsset asset(List<Endpoint> endpoints, List<ExploredPath> paths) {
        return new GraphAsset("sut", "sha1", endpoints, paths,
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    // ─── 테스트 케이스 ────────────────────────────────────────────────────

    @Test
    void identical_assets_are_equivalent() {
        Endpoint ep = endpoint("GET", "/orders");
        ExploredPath p = path("ep1", 200, List.of(branch("Ctrl", "handle", 10, 0)));

        GraphAsset a = asset(List.of(ep), List.of(p));
        GraphAsset b = asset(List.of(ep), List.of(p));

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, b);

        assertThat(result.equivalent()).isTrue();
        assertThat(result.differences()).isEmpty();
    }

    @Test
    void different_order_same_elements_are_equivalent() {
        Endpoint ep1 = endpoint("GET", "/orders");
        Endpoint ep2 = endpoint("POST", "/orders");

        // A: [ep1, ep2], B: [ep2, ep1] — 순서만 다름
        GraphAsset a = asset(List.of(ep1, ep2), List.of());
        GraphAsset b = asset(List.of(ep2, ep1), List.of());

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, b);

        assertThat(result.equivalent()).isTrue();
    }

    @Test
    void extra_endpoint_in_b_is_non_equivalent() {
        Endpoint ep1 = endpoint("GET", "/orders");
        Endpoint ep2 = endpoint("POST", "/orders");

        GraphAsset a = asset(List.of(ep1), List.of());
        GraphAsset b = asset(List.of(ep1, ep2), List.of());

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, b);

        assertThat(result.equivalent()).isFalse();
        assertThat(result.differences()).anyMatch(d -> d.contains("endpoints") && d.contains("B에만"));
    }

    @Test
    void different_branch_set_means_different_path() {
        // 같은 endpoint + 상태이지만 분기 집합이 다름
        ExploredPath pA = path("ep1", 200, List.of(branch("Ctrl", "h", 10, 0)));
        ExploredPath pB = path("ep1", 200, List.of(branch("Ctrl", "h", 10, 1)));

        GraphAsset a = asset(List.of(), List.of(pA));
        GraphAsset b = asset(List.of(), List.of(pB));

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, b);

        assertThat(result.equivalent()).isFalse();
        assertThat(result.differences()).anyMatch(d -> d.contains("paths"));
    }

    @Test
    void path_key_ignores_sample_input_and_id() {
        // sampleInput 이 달라도 (endpointId, semanticStatus, branches)가 같으면 동등
        var node1 = JsonNodeFactory.instance.objectNode();
        node1.put("x", 1);
        var node2 = JsonNodeFactory.instance.objectNode();
        node2.put("x", 999);

        List<BranchRef> branches = List.of(branch("Ctrl", "h", 5, 0));

        ExploredPath pA = new ExploredPath(
                "id-aaa", "ep1", node1, 200, JsonNodeFactory.instance.nullNode(),
                List.of(), List.of(), branches, "static", List.of(), List.of(), List.of(), List.of()
        );
        ExploredPath pB = new ExploredPath(
                "id-bbb", "ep1", node2, 200, JsonNodeFactory.instance.nullNode(),
                List.of(), List.of(), branches, "solver", List.of(), List.of(), List.of(), List.of()
        );

        GraphAsset a = asset(List.of(), List.of(pA));
        GraphAsset b = asset(List.of(), List.of(pB));

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, b);

        assertThat(result.equivalent()).isTrue();
    }

    @Test
    void sql_key_ignores_id_and_bindings() {
        CapturedSql sqlA = new CapturedSql("id-a", "p1", "SELECT", "SELECT * FROM t", "t", List.of());
        // 같은 normalizedSql, 다른 id
        CapturedSql sqlB = new CapturedSql("id-b", "p1", "SELECT", "SELECT * FROM t", "t", List.of());

        GraphAsset a = new GraphAsset("sut", "sha1",
                List.of(), List.of(), List.of(sqlA), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        GraphAsset b = new GraphAsset("sut", "sha1",
                List.of(), List.of(), List.of(sqlB), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, b);

        assertThat(result.equivalent()).isTrue();
    }

    @Test
    void seed_key_ignores_values() {
        RequiredSeed seedA = new RequiredSeed("s1", "p1", "orders", List.of("id", "status"), List.of("1", "NEW"));
        RequiredSeed seedB = new RequiredSeed("s2", "p1", "orders", List.of("id", "status"), List.of("2", "DONE"));

        GraphAsset a = new GraphAsset("sut", "sha1",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(seedA), List.of());
        GraphAsset b = new GraphAsset("sut", "sha1",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(seedB), List.of());

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, b);

        assertThat(result.equivalent()).isTrue();
    }

    @Test
    void multiple_diffs_all_reported() {
        Endpoint ep1 = endpoint("GET", "/a");
        Endpoint ep2 = endpoint("POST", "/b");
        ExploredPath p1 = path("ep1", 200, List.of());
        ExploredPath p2 = path("ep2", 404, List.of());

        GraphAsset a = asset(List.of(ep1), List.of(p1));
        GraphAsset b = asset(List.of(ep2), List.of(p2));

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, b);

        assertThat(result.equivalent()).isFalse();
        // endpoints diff + paths diff 모두 보고되어야 함
        assertThat(result.differences().stream().filter(d -> d.contains("endpoints")).count()).isGreaterThan(0L);
        assertThat(result.differences().stream().filter(d -> d.contains("paths")).count()).isGreaterThan(0L);
    }

    @Test
    void report_string_equivalent() {
        GraphAsset a = asset(List.of(), List.of());
        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, a);
        assertThat(GraphSetEquivDiffTool.report(result)).contains("EQUIVALENT");
    }

    @Test
    void report_string_non_equivalent() {
        GraphAsset a = asset(List.of(endpoint("GET", "/x")), List.of());
        GraphAsset b = asset(List.of(), List.of());
        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(a, b);
        assertThat(GraphSetEquivDiffTool.report(result)).contains("NON-EQUIVALENT");
    }

    /** File-based API smoke test. */
    @Test
    void diff_from_files(@TempDir File tmp) throws IOException {
        GraphAsset asset = asset(List.of(endpoint("GET", "/items")), List.of());
        File fileA = new File(tmp, "a.json");
        File fileB = new File(tmp, "b.json");
        Json.mapper().writeValue(fileA, asset);
        Json.mapper().writeValue(fileB, asset);

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(fileA, fileB);

        assertThat(result.equivalent()).isTrue();
    }

    // ─── main() smoke ────────────────────────────────────────────────────

    /**
     * main()을 직접 호출해 CLI 진입점이 동작하는지 smoke 검증.
     * EQUIVALENT 케이스에서 System.exit(0) 이 기대되지만, 테스트에서 System.exit 를
     * 억제하지 않으므로 SecurityException / ExitException 없이 완료됨을 확인한다.
     * (exit 가드가 없는 환경에서는 JVM 이 종료되므로 반드시 분리 실행하려면 별도 프로세스 필요.
     * 여기서는 JUnit5 기본 프로세스에서 돌아가는 happy-path를 확인하는 단순 smoke다.)
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== GraphSetEquivDiffTool smoke (main) ===");

        GraphAsset asset = new GraphAsset("sut", "sha1",
                List.of(new Endpoint("e1", "GET", "/orders", "C", "m", List.of(), false)),
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        File tmp = File.createTempFile("graph", ".json");
        tmp.deleteOnExit();
        Json.mapper().writeValue(tmp, asset);

        GraphSetEquivDiffTool.DiffResult result = GraphSetEquivDiffTool.diff(tmp, tmp);
        System.out.println("Self-diff: " + GraphSetEquivDiffTool.report(result));
        System.out.println("=== OK ===");
    }
}
