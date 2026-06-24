package io.graphrag.builder.parallel;

import io.graphrag.model.CapturedEventEmit;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
import io.graphrag.model.KafkaConsumer;
import io.graphrag.model.KafkaExchange;
import io.graphrag.model.RequiredSeed;
import io.graphrag.model.WsEndpoint;
import io.graphrag.model.WsExchange;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * graph.json set-동등 diff 도구 (REQ-P003).
 *
 * <p>두 graph.json 파일을 각 필드별로 집합(set) 단위로 비교한다. 순서 무관.
 * 비결정 필드(타임스탬프, 절대 coverageKey 해시 등)는 stable key로 대체해 비교한다.
 *
 * <h2>Stable Key 설계 방침</h2>
 * <ul>
 *   <li><b>ExploredPath</b>: (endpointId, semanticStatus, sorted-branchesTaken) —
 *       endpointId + 관측 상태 + 분기 집합으로 동일 코드 경로를 식별.
 *       sampleInput·id 같은 실행별 가변 필드는 제외한다.</li>
 *   <li><b>CapturedSql</b>: (pathId, sqlKind, normalizedSql) —
 *       실제 쿼리 텍스트와 종류로 식별. 바인딩 값은 실행마다 다를 수 있어 제외한다.</li>
 *   <li><b>CapturedHttpCall</b>: (pathId, method, urlPath) —
 *       외부 HTTP 호출의 경로+메서드로 식별. 요청/응답 본문 제외.</li>
 *   <li><b>WsExchange</b>: (wsEndpointId, responseDestination) —
 *       WS 교환은 endpoint+목적지로 식별.</li>
 *   <li><b>KafkaConsumer</b>: (topic, handlerClass, handlerMethod) —
 *       consumer 정의는 정적이므로 완전 식별자 사용.</li>
 *   <li><b>KafkaExchange</b>: (kafkaConsumerId, topic) —
 *       교환은 consumer id + 토픽으로 식별. payload 제외.</li>
 *   <li><b>RequiredSeed</b>: (pathId, table, sorted-columns) —
 *       시드 행은 테이블+컬럼 구조로 식별. values는 실행마다 다를 수 있어 제외.</li>
 *   <li><b>CapturedEventEmit</b>: (pathId, topic, key) —
 *       이벤트 발행은 토픽+키로 식별. payload 제외.</li>
 *   <li><b>Endpoint</b>: (httpMethod, path) — HTTP endpoint는 메서드+경로.</li>
 *   <li><b>WsEndpoint</b>: (wsPath, destination) — WS endpoint는 경로+목적지.</li>
 * </ul>
 */
public final class GraphSetEquivDiffTool {

    /** set-동등 비교 결과. */
    public record DiffResult(boolean equivalent, List<String> differences) {}

    private GraphSetEquivDiffTool() {}

    /**
     * 두 graph.json 파일을 로드하여 set-동등 여부를 판단한다.
     */
    public static DiffResult diff(File fileA, File fileB) throws IOException {
        GraphAsset a = Json.mapper().readValue(fileA, GraphAsset.class);
        GraphAsset b = Json.mapper().readValue(fileB, GraphAsset.class);
        return diff(a, b);
    }

    /**
     * 두 GraphAsset 인스턴스를 set-동등 비교한다.
     */
    public static DiffResult diff(GraphAsset a, GraphAsset b) {
        List<String> diffs = new ArrayList<>();

        diffSets("endpoints",          a.endpoints(),           b.endpoints(),           GraphSetEquivDiffTool::endpointKey,          diffs);
        diffSets("wsEndpoints",        a.wsEndpoints(),         b.wsEndpoints(),         GraphSetEquivDiffTool::wsEndpointKey,        diffs);
        diffSets("paths",              a.paths(),               b.paths(),               GraphSetEquivDiffTool::exploredPathKey,      diffs);
        diffSets("sql",                a.sql(),                 b.sql(),                 GraphSetEquivDiffTool::capturedSqlKey,       diffs);
        diffSets("httpCalls",          a.httpCalls(),           b.httpCalls(),           GraphSetEquivDiffTool::capturedHttpCallKey,  diffs);
        diffSets("wsExchanges",        a.wsExchanges(),         b.wsExchanges(),         GraphSetEquivDiffTool::wsExchangeKey,        diffs);
        diffSets("kafkaConsumers",     a.kafkaConsumers(),      b.kafkaConsumers(),      GraphSetEquivDiffTool::kafkaConsumerKey,     diffs);
        diffSets("kafkaExchanges",     a.kafkaExchanges(),      b.kafkaExchanges(),      GraphSetEquivDiffTool::kafkaExchangeKey,     diffs);
        diffSets("seeds",              a.seeds(),               b.seeds(),               GraphSetEquivDiffTool::requiredSeedKey,      diffs);
        diffSets("capturedEventEmits", a.capturedEventEmits(),  b.capturedEventEmits(),  GraphSetEquivDiffTool::capturedEventEmitKey, diffs);

        return new DiffResult(diffs.isEmpty(), List.copyOf(diffs));
    }

    /** 사람이 읽기 쉬운 리포트 문자열 */
    public static String report(DiffResult result) {
        if (result.equivalent()) {
            return "EQUIVALENT — 모든 집합이 set-동등입니다.";
        }
        StringBuilder sb = new StringBuilder("NON-EQUIVALENT — 차이 항목:\n");
        for (String d : result.differences()) {
            sb.append("  ").append(d).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ─── Stable key 함수 ──────────────────────────────────────────────────

    /** ExploredPath key: (endpointId, semanticStatus, sorted-branchesTaken) */
    static String exploredPathKey(ExploredPath p) {
        String branches = p.branchesTaken().stream()
                .map(b -> b.classFqn() + "#" + b.method() + ":" + b.line() + ":" + b.branchIndex())
                .sorted()
                .collect(Collectors.joining(","));
        return p.endpointId() + "|" + p.semanticStatus() + "|[" + branches + "]";
    }

    /** CapturedSql key: (pathId, sqlKind, normalizedSql) */
    static String capturedSqlKey(CapturedSql s) {
        return s.pathId() + "|" + s.sqlKind() + "|" + s.normalizedSql();
    }

    /** CapturedHttpCall key: (pathId, method, urlPath) */
    static String capturedHttpCallKey(CapturedHttpCall h) {
        return h.pathId() + "|" + h.method() + "|" + h.urlPath();
    }

    /** WsExchange key: (wsEndpointId, responseDestination) */
    static String wsExchangeKey(WsExchange w) {
        return w.wsEndpointId() + "|" + w.responseDestination();
    }

    /** KafkaConsumer key: (topic, handlerClass, handlerMethod) */
    static String kafkaConsumerKey(KafkaConsumer k) {
        return k.topic() + "|" + k.handlerClass() + "|" + k.handlerMethod();
    }

    /** KafkaExchange key: (kafkaConsumerId, topic) */
    static String kafkaExchangeKey(KafkaExchange k) {
        return k.kafkaConsumerId() + "|" + k.topic();
    }

    /**
     * RequiredSeed key: (pathId, table, sorted-columns).
     * values 는 실행마다 다를 수 있어 제외한다.
     */
    static String requiredSeedKey(RequiredSeed s) {
        String cols = s.columns().stream().sorted().collect(Collectors.joining(","));
        return s.pathId() + "|" + s.table() + "|[" + cols + "]";
    }

    /** CapturedEventEmit key: (pathId, topic, key) */
    static String capturedEventEmitKey(CapturedEventEmit e) {
        return e.pathId() + "|" + e.topic() + "|" + e.key();
    }

    /** Endpoint key: (httpMethod, path) */
    static String endpointKey(Endpoint e) {
        return e.httpMethod() + " " + e.path();
    }

    /** WsEndpoint key: (wsPath, destination) */
    static String wsEndpointKey(WsEndpoint w) {
        return w.wsPath() + "|" + w.destination();
    }

    // ─── 공통 set-diff 로직 ───────────────────────────────────────────────

    @FunctionalInterface
    interface KeyFn<T> {
        String key(T item);
    }

    private static <T> void diffSets(
            String fieldName,
            List<T> listA,
            List<T> listB,
            KeyFn<T> keyFn,
            List<String> diffs) {

        Set<String> setA = listA.stream().map(keyFn::key).collect(Collectors.toCollection(HashSet::new));
        Set<String> setB = listB.stream().map(keyFn::key).collect(Collectors.toCollection(HashSet::new));

        Set<String> onlyInA = new HashSet<>(setA);
        onlyInA.removeAll(setB);

        Set<String> onlyInB = new HashSet<>(setB);
        onlyInB.removeAll(setA);

        if (!onlyInA.isEmpty()) {
            diffs.add(fieldName + ": A에만 있음 (" + onlyInA.size() + "건): " + summarize(onlyInA));
        }
        if (!onlyInB.isEmpty()) {
            diffs.add(fieldName + ": B에만 있음 (" + onlyInB.size() + "건): " + summarize(onlyInB));
        }
    }

    private static String summarize(Set<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        if (sorted.size() <= 5) {
            return String.join("; ", sorted);
        }
        return sorted.subList(0, 5).stream().collect(Collectors.joining("; "))
                + " … (" + (sorted.size() - 5) + "개 더)";
    }

    // ─── CLI entry point ──────────────────────────────────────────────────

    /**
     * 셸에서 직접 실행할 수 있는 진입점.
     * Usage: java -cp ... GraphSetEquivDiffTool graph-a.json graph-b.json
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: GraphSetEquivDiffTool <graph-a.json> <graph-b.json>");
            System.exit(1);
        }
        DiffResult result = diff(new File(args[0]), new File(args[1]));
        System.out.println(report(result));
        System.exit(result.equivalent() ? 0 : 1);
    }
}
