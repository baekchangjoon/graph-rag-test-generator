package io.graphrag.builder.run;

import io.graphrag.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL-기반 ResolutionHint 주입 시 시드 타깃/컬럼이 hint를 따르는지 검증. */
class ReadInputSynthesizerHintTest {

    private static TableSchema moodPoint() {
        return new TableSchema("mood_point",
                List.of(new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("user_id", "VARCHAR", false, false),
                        new ColumnSchema("source", "VARCHAR", false, false),
                        new ColumnSchema("label", "VARCHAR", false, false),
                        new ColumnSchema("score", "INT", false, false),
                        new ColumnSchema("occurred_at", "TIMESTAMP", false, false)),
                List.of(), List.of());
    }

    @Test
    void nullHint_behavesLikeHeuristic() {
        // 기존 동작 회귀: hint=null 이면 무-hint synthesize와 동일
        Endpoint e = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}", "x.C", "get",
                List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), true);
        TableSchema orders = new TableSchema("orders",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("status", "VARCHAR", false, false)),
                List.of(), List.of());

        SynthesizedInput withNull = new ReadInputSynthesizer().synthesize(e, List.of(orders), null);
        SynthesizedInput heuristic = new ReadInputSynthesizer().synthesize(e, List.of(orders));

        assertThat(withNull.seeds()).hasSize(heuristic.seeds().size());
        assertThat(withNull.seeds().get(0).table()).isEqualTo("orders");
    }

    @Test
    void hint_mapsPathVarToNonPkColumn_andSeedMatchesBody() {
        // analytics getUserMood: PATH userId → 비-PK user_id (휴리스틱은 PK로 잘못 매핑)
        Endpoint e = new Endpoint("get-mood-userid", "GET", "/internal/analytics/mood/{userId}",
                "x.C", "getUserMood",
                List.of(new EndpointParam("userId", "java.lang.String", ParamKind.PATH)), false);
        ResolutionHint hint = new ResolutionHint("mood_point", Map.of("userId", "user_id"));

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(e, List.of(moodPoint()), hint);

        assertThat(out.seeds()).hasSize(1);
        SynthesizedInput.SeedRow seed = out.seeds().get(0);
        assertThat(seed.table()).isEqualTo("mood_point");
        int userIdx = seed.columns().indexOf("user_id");
        assertThat(userIdx).isGreaterThanOrEqualTo(0);
        // 시드 user_id 값 == URL로 보낼 userId 값 (WHERE user_id=? 매칭)
        assertThat(seed.values().get(userIdx).toString())
                .isEqualTo(out.body().get("userId").asText());
        // PK id 는 비충돌 probe 로 채워짐 (varchar PK → 문자열)
        int idIdx = seed.columns().indexOf("id");
        assertThat(seed.values().get(idIdx).toString()).startsWith("probe-");
    }

    @Test
    void hint_pkPathVar_graphRecord() {
        // mindgraph byDiary: PATH diaryId → diary_id (PK)
        Endpoint e = new Endpoint("get-graph-diaryid", "GET", "/internal/graphs/diary/{diaryId}",
                "x.C", "byDiary",
                List.of(new EndpointParam("diaryId", "java.lang.String", ParamKind.PATH)), false);
        TableSchema graphRecord = new TableSchema("graph_record",
                List.of(new ColumnSchema("diary_id", "VARCHAR", false, true),
                        new ColumnSchema("user_id", "VARCHAR", false, false),
                        new ColumnSchema("nodes_json", "TEXT", false, false),
                        new ColumnSchema("links_json", "TEXT", false, false),
                        new ColumnSchema("updated_at", "TIMESTAMP", false, false)),
                List.of(), List.of());
        ResolutionHint hint = new ResolutionHint("graph_record", Map.of("diaryId", "diary_id"));

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(e, List.of(graphRecord), hint);

        SynthesizedInput.SeedRow seed = out.seeds().get(0);
        assertThat(seed.table()).isEqualTo("graph_record");
        assertThat(seed.columns().get(0)).isEqualTo("diary_id");   // PK first
        assertThat(out.body().get("diaryId").asText())
                .isEqualTo(seed.values().get(0).toString());
    }

    @Test
    void hint_emptyParamColumn_getGlobal_seedsPkAndNotNullOnly() {
        // analytics getGlobal: param 없음, FROM mood_point 만으로 시드 (집계용 1행)
        Endpoint e = new Endpoint("get-global", "GET", "/internal/analytics/global",
                "x.C", "getGlobal", List.of(), false);
        ResolutionHint hint = new ResolutionHint("mood_point", Map.of());

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(e, List.of(moodPoint()), hint);

        SynthesizedInput.SeedRow seed = out.seeds().get(0);
        assertThat(seed.table()).isEqualTo("mood_point");
        // PK + 모든 NOT NULL 컬럼 시드
        assertThat(seed.columns()).contains("id", "user_id", "source", "label", "score", "occurred_at");
    }

    @Test
    void hint_integerNonPkColumn_coercedToInt() {
        Endpoint e = new Endpoint("get-x-rank", "GET", "/x/{rank}", "x.C", "get",
                List.of(new EndpointParam("rank", "java.lang.Integer", ParamKind.PATH)), false);
        TableSchema t = new TableSchema("widget",
                List.of(new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("rank", "INT", false, false)),
                List.of(), List.of());
        ResolutionHint hint = new ResolutionHint("widget", Map.of("rank", "rank"));

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(e, List.of(t), hint);

        SynthesizedInput.SeedRow seed = out.seeds().get(0);
        int rankIdx = seed.columns().indexOf("rank");
        assertThat(seed.values().get(rankIdx)).isInstanceOf(Integer.class);
    }
}
