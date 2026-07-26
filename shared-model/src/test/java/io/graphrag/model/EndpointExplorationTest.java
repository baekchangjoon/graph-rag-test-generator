package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-021: EndpointExploration trial observation fields — round-trip and backward-compat.
 */
class EndpointExplorationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("REQ-021: trial observation fields round-trip serialize/deserialize")
    void testTrialObservationFieldsRoundTrip() throws Exception {
        // Arrange: create EndpointExploration with all trial observation fields
        var staleTriples = List.of(
                "ep-1/promoted/cand-01",
                "ep-1/promoted/cand-02"
        );
        var tripleRejected = Map.of(
                "semantic-error", 3,
                "syntax-error", 1
        );

        var original = new ExplorationReport.EndpointExploration(
                "GET /users",
                100,
                75,
                List.of(),
                Map.of("solver-a", 50),
                5,
                List.of(),
                null,
                42,                    // trialCount
                true,                  // tripleAdopted
                tripleRejected,        // tripleRejected
                staleTriples           // staleTriples
        );

        // Act: serialize to JSON, then deserialize
        String json = MAPPER.writeValueAsString(original);
        var deserialized = MAPPER.readValue(json, ExplorationReport.EndpointExploration.class);

        // Assert: all trial observation fields preserved
        assertEquals(42, deserialized.trialCount());
        assertTrue(deserialized.tripleAdopted());
        assertEquals(tripleRejected, deserialized.tripleRejected());
        assertEquals(staleTriples, deserialized.staleTriples());
    }

    @Test
    @DisplayName("REQ-021: backward-compat deserialize old JSON (no trial fields) → defaults")
    void testBackwardCompatOldJsonNoTrialFields() throws Exception {
        // Arrange: old JSON without trial observation fields
        String oldJson = """
                {
                  "endpointId": "GET /users",
                  "totalBranches": 100,
                  "coveredBranches": 75,
                  "missedBranches": [],
                  "pathsByEngine": {"solver-a": 50},
                  "solverRelevantMissed": 5,
                  "droppedPaths": [],
                  "noHappyPathReason": null
                }
                """;

        // Act: deserialize old JSON
        var deserialized = MAPPER.readValue(oldJson, ExplorationReport.EndpointExploration.class);

        // Assert: trial fields default to 0, false, empty collections
        assertEquals(0, deserialized.trialCount());
        assertFalse(deserialized.tripleAdopted());
        assertEquals(Map.of(), deserialized.tripleRejected());
        assertEquals(List.of(), deserialized.staleTriples());
    }

    @Test
    @DisplayName("REQ-021: 8-arg backward-compat constructor (pre-trial era)")
    void testEightArgBackwardCompatConstructor() {
        // Arrange & Act: create via 8-arg constructor (no trial fields)
        var exploration = new ExplorationReport.EndpointExploration(
                "GET /users",
                100,
                75,
                List.of(),
                Map.of("solver-a", 50),
                5,
                List.of(),
                null
        );

        // Assert: trial fields are initialized to defaults
        assertEquals(0, exploration.trialCount());
        assertFalse(exploration.tripleAdopted());
        assertEquals(Map.of(), exploration.tripleRejected());
        assertEquals(List.of(), exploration.staleTriples());
    }

    @Test
    @DisplayName("REQ-021: existing 7-arg backward-compat constructor still works")
    void testSevenArgBackwardCompatConstructor() {
        // Arrange & Act: create via 7-arg constructor (legacy, no noHappyPathReason)
        var exploration = new ExplorationReport.EndpointExploration(
                "GET /users",
                100,
                75,
                List.of(),
                Map.of("solver-a", 50),
                5,
                List.of()
        );

        // Assert: legacy fields work + trial fields default to 0, false, empty
        assertEquals("GET /users", exploration.endpointId());
        assertEquals(100, exploration.totalBranches());
        assertEquals(0, exploration.trialCount());
        assertFalse(exploration.tripleAdopted());
        assertEquals(Map.of(), exploration.tripleRejected());
        assertEquals(List.of(), exploration.staleTriples());
    }

    @Test
    @DisplayName("REQ-021: existing 6-arg backward-compat constructor still works")
    void testSixArgBackwardCompatConstructor() {
        // Arrange & Act: create via 6-arg constructor (oldest legacy)
        var exploration = new ExplorationReport.EndpointExploration(
                "GET /users",
                100,
                75,
                List.of(),
                Map.of("solver-a", 50),
                5
        );

        // Assert: legacy fields work + trial fields default to 0, false, empty
        assertEquals("GET /users", exploration.endpointId());
        assertEquals(100, exploration.totalBranches());
        assertEquals(0, exploration.trialCount());
        assertFalse(exploration.tripleAdopted());
        assertEquals(Map.of(), exploration.tripleRejected());
        assertEquals(List.of(), exploration.staleTriples());
    }

    @Test
    @DisplayName("REQ-021: staleTriples format — endpoint/promoted/cand-NN")
    void testStaleTriplesFormat() {
        // Arrange: staleTriples with proper format (store-root-relative paths)
        var staleTriples = List.of(
                "ep-123/promoted/cand-00",
                "ep-456/promoted/cand-15",
                "another-ep/promoted/cand-99"
        );

        var exploration = new ExplorationReport.EndpointExploration(
                "GET /test",
                10, 8, List.of(),
                Map.of("engine", 5),
                1,
                List.of(),
                null,
                5,
                true,
                Map.of(),
                staleTriples
        );

        // Assert: staleTriples preserved exactly
        assertEquals(staleTriples, exploration.staleTriples());
    }

    @Test
    @DisplayName("REQ-021: null-guard on tripleRejected and staleTriples → empty")
    void testNullGuardTrialFields() {
        // Arrange & Act: create with null trial fields
        var exploration = new ExplorationReport.EndpointExploration(
                "GET /test",
                10, 8, List.of(),
                Map.of("engine", 5),
                1,
                List.of(),
                null,
                10,
                false,
                null,        // tripleRejected null
                null         // staleTriples null
        );

        // Assert: null-guarded to empty collections
        assertEquals(Map.of(), exploration.tripleRejected());
        assertEquals(List.of(), exploration.staleTriples());
    }
}
