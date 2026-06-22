package io.graphrag.builder.poc.fanout;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

/** PoC: pjacoco 제어 엔드포인트로 per-request 경계를 긋고, flush된 <testId>.exec 를 store로 로드. */
public final class PjacocoCoverageClient {
    private final String controlHost;
    private final int controlPort;
    private final Path destfileDir;
    private final HttpClient http = HttpClient.newHttpClient();

    public PjacocoCoverageClient(String controlHost, int controlPort, Path destfileDir) {
        this.controlHost = controlHost;
        this.controlPort = controlPort;
        this.destfileDir = destfileDir;
    }

    public void startTest(String testId) { post("/__coverage__/test/start?testId=" + testId); }
    public void stopTest(String testId) { post("/__coverage__/test/stop?testId=" + testId + "&result=passed"); }

    private void post(String path) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://" + controlHost + ":" + controlPort + path))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new IllegalStateException("pjacoco control " + path + " -> " + r.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new UncheckedIOException("pjacoco control failed: " + path,
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    public ExecutionDataStore load(String testId) {
        try {
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(destfileDir.resolve(testId + ".exec").toFile());
            return loader.getExecutionDataStore();
        } catch (IOException e) {
            throw new UncheckedIOException("pjacoco exec load failed for " + testId, e);
        }
    }
}
