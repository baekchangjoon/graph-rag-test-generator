package io.graphrag.builder.coverage;

import io.graphrag.builder.capture.TraceParent;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-P002: fixture .exec round-trip — pjacoco stop이 쓴 <testId>.exec를 백엔드가 로드하는지 검증. */
class PjacocoCoverageBackendTest {

    private static final TraceParent GEN = new TraceParent("test-run");

    @Test
    void loadExec_readsFixtureExecIntoStore(@TempDir Path dir) throws Exception {
        // pjacoco가 stop 시 쓸 <traceId>.exec를 흉내낸 fixture
        String traceId = GEN.next().traceId();
        try (FileOutputStream out = new FileOutputStream(dir.resolve(traceId + ".exec").toFile())) {
            ExecutionDataWriter writer = new ExecutionDataWriter(out);
            writer.visitClassExecution(new ExecutionData(
                    0x1234L, "com/example/Foo", new boolean[]{true, false, true}));
        }

        PjacocoCoverageBackend backend = new PjacocoCoverageBackend("127.0.0.1", 0, dir);
        ExecutionDataStore store = backend.loadExec(traceId);

        assertThat(store.getContents()).anySatisfy(ed ->
                assertThat(ed.getName()).isEqualTo("com/example/Foo"));
    }

    @Test
    void awaitExec_returnsStoreWhenFileAlreadyPresent(@TempDir Path dir) throws Exception {
        String traceId = GEN.next().traceId();
        try (FileOutputStream out = new FileOutputStream(dir.resolve(traceId + ".exec").toFile())) {
            ExecutionDataWriter writer = new ExecutionDataWriter(out);
            writer.visitClassExecution(new ExecutionData(
                    0x5678L, "com/example/Bar", new boolean[]{true, true, false}));
        }

        PjacocoCoverageBackend backend = new PjacocoCoverageBackend("127.0.0.1", 0, dir);
        ExecutionDataStore store = backend.awaitExec(traceId, 500);

        assertThat(store.getContents()).anySatisfy(ed ->
                assertThat(ed.getName()).isEqualTo("com/example/Bar"));
    }

    @Test
    void awaitExec_returnsEmptyStoreOnTimeout(@TempDir Path dir) {
        String traceId = GEN.next().traceId();

        PjacocoCoverageBackend backend = new PjacocoCoverageBackend("127.0.0.1", 0, dir);
        // no .exec file → should return empty store, not throw
        ExecutionDataStore store = backend.awaitExec(traceId, 300);

        assertThat(store.getContents()).isEmpty();
    }

    @Test
    void traceparentFor_hasCorrectFormat() {
        String traceId = GEN.next().traceId();
        String traceparent = PjacocoCoverageBackend.traceparentFor(traceId);

        assertThat(traceparent).startsWith("00-" + traceId + "-");
        assertThat(traceparent).endsWith("-01");
    }

    /**
     * REQ-P008: N개 스레드에서 동시에 awaitExec(고유 traceId)를 호출해도
     * 예외 없이 각 traceId별로 올바른 store를 반환한다.
     * — per-worker-synchronous 모델의 동시성 안전성 검증.
     */
    @Test
    void awaitExec_concurrentDistinctTraceIds_noExceptionAndCorrectStores(@TempDir Path dir) throws Exception {
        int workerCount = 8;
        TraceParent gen = new TraceParent("concurrency-test");

        // 각 워커가 쓸 고유 traceId와 fixture .exec를 미리 준비
        List<String> traceIds = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) {
            String traceId = gen.next().traceId();
            traceIds.add(traceId);
            try (FileOutputStream out = new FileOutputStream(dir.resolve(traceId + ".exec").toFile())) {
                ExecutionDataWriter writer = new ExecutionDataWriter(out);
                // classId를 traceId별로 구분하기 위해 인덱스(i)를 classId로 활용
                writer.visitClassExecution(new ExecutionData(
                        (long) i, "com/example/Class" + i, new boolean[]{true}));
            }
        }

        PjacocoCoverageBackend backend = new PjacocoCoverageBackend("127.0.0.1", 0, dir, 1_000L);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        try {
            List<Callable<ExecutionDataStore>> tasks = new ArrayList<>();
            for (String traceId : traceIds) {
                tasks.add(() -> backend.awaitExec(traceId, 500));
            }
            List<Future<ExecutionDataStore>> futures = pool.invokeAll(tasks);

            for (int i = 0; i < workerCount; i++) {
                ExecutionDataStore store = futures.get(i).get();   // 예외 전파 확인
                String expectedClass = "com/example/Class" + i;
                assertThat(store.getContents())
                        .as("worker %d should see class %s in its store", i, expectedClass)
                        .anySatisfy(ed -> assertThat(ed.getName()).isEqualTo(expectedClass));
            }
        } finally {
            pool.shutdown();
            backend.shutdown();
        }
    }
}
