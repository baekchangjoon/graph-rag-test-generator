package io.graphrag.builder.persistence;

import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.HttpClientType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphArchiveHttpRoundTripTest {

    private CapturedHttpCall sample(String pathId, String url, int status) {
        return new CapturedHttpCall(
                "h-" + pathId, pathId, "GET", url, url, List.of(),
                Map.of(), null, List.of(),
                status, "{}", List.of(),
                HttpClientType.OTHER, "ext");
    }

    @Test
    void addAndQueryByPathId(@TempDir Path tmp) {
        GraphArchive a = new GraphArchive(tmp);
        a.addCapturedHttpCall(sample("p1", "/x", 200));
        a.addCapturedHttpCall(sample("p1", "/y", 200));
        a.addCapturedHttpCall(sample("p2", "/z", 404));

        assertThat(a.capturedHttpByPath("p1")).hasSize(2);
        assertThat(a.capturedHttpByPath("p2")).hasSize(1);
        assertThat(a.capturedHttpByPath("missing")).isEmpty();
    }

    @Test
    void saveAndLoadPreservesHttpCalls(@TempDir Path tmp) throws IOException {
        GraphArchive original = new GraphArchive(tmp);
        original.addCapturedHttpCall(sample("p1", "/api", 200));
        original.addCapturedHttpCall(sample("p1", "/api2", 201));
        original.save();

        GraphArchive loaded = GraphArchive.load(tmp);

        assertThat(loaded.capturedHttpByPath("p1")).hasSize(2);
        assertThat(tmp.resolve("captured_http.json").toFile()).exists();
    }
}
