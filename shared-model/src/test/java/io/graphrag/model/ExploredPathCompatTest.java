package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExploredPathCompatTest {

    private static final ObjectMapper MAPPER = Json.mapper();

    @Test
    void legacyConstructorWith200IsSuccess() {
        ExploredPath p = new ExploredPath("id", "ep", null, 200, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(), List.of(), Map.of());
        assertThat(p.outcome()).isEqualTo(Outcome.Kind.SUCCESS);
        assertThat(p.semanticStatus()).isEqualTo(200);
    }

    @Test
    void legacyConstructorWith404IsFailure() {
        ExploredPath p = new ExploredPath("id", "ep", null, 404, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(), List.of(), Map.of());
        assertThat(p.outcome()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(p.semanticStatus()).isEqualTo(404);
    }

    /** 역직렬화 후방호환: outcome/semanticStatus/semanticStatusText 누락 시 expectedStatus에서 파생 */
    @Test
    void jacksonDeserializeLegacyJson200DerivesSuccess() throws Exception {
        String json = """
                {"id":"p1","endpointId":"ep","sampleInput":null,"expectedStatus":200,
                 "sampleResponse":null,"capturedSqlIds":[],"capturedHttpCallIds":[],
                 "branchesTaken":[],"discoveredBy":"heuristic","constraints":[],
                 "validationWarnings":[],"requiredSeedIds":[],"capturedEventEmitIds":[],
                 "responseHeaders":{}}
                """;
        ExploredPath p = MAPPER.readValue(json, ExploredPath.class);
        assertThat(p.outcome()).isEqualTo(Outcome.Kind.SUCCESS);
        assertThat(p.semanticStatus()).isEqualTo(200);
        assertThat(p.semanticStatusText()).isEqualTo("200");
    }

    @Test
    void jacksonDeserializeLegacyJson404DerivesFailure() throws Exception {
        String json = """
                {"id":"p2","endpointId":"ep","sampleInput":null,"expectedStatus":404,
                 "sampleResponse":null,"capturedSqlIds":[],"capturedHttpCallIds":[],
                 "branchesTaken":[],"discoveredBy":"heuristic","constraints":[],
                 "validationWarnings":[],"requiredSeedIds":[],"capturedEventEmitIds":[],
                 "responseHeaders":{}}
                """;
        ExploredPath p = MAPPER.readValue(json, ExploredPath.class);
        assertThat(p.outcome()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(p.semanticStatus()).isEqualTo(404);
        assertThat(p.semanticStatusText()).isEqualTo("404");
    }

    /** 신규 형식(outcome 명시)은 명시 값이 그대로 보존되어야 함 */
    @Test
    void jacksonDeserializeNewJsonPreservesExplicitOutcome() throws Exception {
        String json = """
                {"id":"p3","endpointId":"ep","sampleInput":null,"expectedStatus":200,
                 "sampleResponse":null,"capturedSqlIds":[],"capturedHttpCallIds":[],
                 "branchesTaken":[],"discoveredBy":"heuristic","constraints":[],
                 "validationWarnings":[],"requiredSeedIds":[],"capturedEventEmitIds":[],
                 "responseHeaders":{},"outcome":"FAILURE","semanticStatus":422,
                 "semanticStatusText":"Unprocessable Entity"}
                """;
        ExploredPath p = MAPPER.readValue(json, ExploredPath.class);
        assertThat(p.outcome()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(p.semanticStatus()).isEqualTo(422);
        assertThat(p.semanticStatusText()).isEqualTo("Unprocessable Entity");
    }

    @Test
    void legacyJsonYieldsEmptyCoverageTraceIds() throws Exception {
        String json = """
                {"id":"p1","endpointId":"ep","sampleInput":null,"expectedStatus":200,
                 "sampleResponse":null,"capturedSqlIds":[],"capturedHttpCallIds":[],
                 "branchesTaken":[],"discoveredBy":"heuristic","constraints":[],
                 "validationWarnings":[],"requiredSeedIds":[],"capturedEventEmitIds":[],
                 "responseHeaders":{}}
                """;
        ExploredPath p = MAPPER.readValue(json, ExploredPath.class);
        assertThat(p.coverageTraceIds()).isEmpty();
    }

    @Test
    void nullCoverageTraceIdsNormalizedToEmpty() throws Exception {
        String json = """
                {"id":"p1","endpointId":"ep","expectedStatus":200,"coverageTraceIds":null}
                """;
        ExploredPath p = MAPPER.readValue(json, ExploredPath.class);
        assertThat(p.coverageTraceIds()).isEmpty();
    }

    @Test
    void roundTripPreservesCoverageTraceIds() throws Exception {
        ExploredPath p = new ExploredPath("p1", "ep", null, 200, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Outcome.Kind.SUCCESS, 200, "200", List.of("abc", "def"));
        ExploredPath rt = MAPPER.readValue(MAPPER.writeValueAsString(p), ExploredPath.class);
        assertThat(rt.coverageTraceIds()).containsExactly("abc", "def");
    }
}
