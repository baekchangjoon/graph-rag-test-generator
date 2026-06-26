package io.graphrag.builder.coverage;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.capture.TraceParent;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PjacocoCoverageBackendBinaryStopTest {

    private static final TraceParent GEN = new TraceParent("binary-stop-test");

    private HttpServer server;
    private int port;
    private Path execDir;
    private String traceId;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        execDir = dir;
        traceId = GEN.next().traceId();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void stopAndLoad_readsBinaryBody() throws Exception {
        byte[] execBytes = fixtureExecBytes(traceId);
        server.createContext("/__coverage__/test/stop", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, execBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(execBytes);
            }
            exchange.close();
        });

        PjacocoCoverageBackend backend = new PjacocoCoverageBackend("127.0.0.1", port, execDir);
        PjacocoCoverageBackend.StopLoadOutcome outcome = backend.stopAndLoad(traceId, false);

        assertThat(outcome.path()).isEqualTo(PjacocoCoverageBackend.StopLoadPath.BINARY);
        assertThat(outcome.store().getContents()).anySatisfy(ed ->
                assertThat(ed.getName()).isEqualTo("com/example/Foo"));
    }

    @Test
    void stopAndLoad_empty204() {
        server.createContext("/__coverage__/test/stop", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        PjacocoCoverageBackend backend = new PjacocoCoverageBackend("127.0.0.1", port, execDir);
        PjacocoCoverageBackend.StopLoadOutcome outcome = backend.stopAndLoad(traceId, false);

        assertThat(outcome.path()).isEqualTo(PjacocoCoverageBackend.StopLoadPath.BINARY);
        assertThat(outcome.store().getContents()).isEmpty();
    }

    @Test
    void stopAndLoad_fallbackOnTextResponse(@TempDir Path dir) throws Exception {
        try (FileOutputStream out = new FileOutputStream(dir.resolve(traceId + ".exec").toFile())) {
            ExecutionDataWriter writer = new ExecutionDataWriter(out);
            writer.visitClassExecution(new ExecutionData(
                    0x9999L, "com/example/Legacy", new boolean[]{true, false}));
        }

        server.createContext("/__coverage__/test/stop", exchange -> {
            byte[] body = ("stopped " + traceId).getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });

        PjacocoCoverageProbe probe = new PjacocoCoverageProbe(
                new PjacocoCoverageBackend("127.0.0.1", port, dir, 500L));
        ExecutionDataStore store = probe.requestDelta(traceId);

        assertThat(store.getContents()).anySatisfy(ed ->
                assertThat(ed.getName()).isEqualTo("com/example/Legacy"));
    }

    @Test
    void requestDelta_noTimeoutOnBinary() throws Exception {
        byte[] execBytes = fixtureExecBytes(traceId);
        server.createContext("/__coverage__/test/stop", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, execBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(execBytes);
            }
            exchange.close();
        });

        PjacocoCoverageProbe probe = new PjacocoCoverageProbe(
                new PjacocoCoverageBackend("127.0.0.1", port, execDir, 30_000L));
        Instant start = Instant.now();
        ExecutionDataStore store = probe.requestDelta(traceId);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(store.getContents()).isNotEmpty();
    }

    private static byte[] fixtureExecBytes(String testId) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ExecutionDataWriter writer = new ExecutionDataWriter(buffer);
        writer.visitClassExecution(new ExecutionData(
                0x1234L, "com/example/Foo", new boolean[]{true, false, true}));
        return buffer.toByteArray();
    }
}
