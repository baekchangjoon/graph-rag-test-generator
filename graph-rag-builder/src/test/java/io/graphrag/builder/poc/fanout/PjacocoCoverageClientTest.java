package io.graphrag.builder.poc.fanout;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.junit.jupiter.api.Test;
import java.io.FileOutputStream;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PjacocoCoverageClientTest {
    @Test
    void load_readsExecFileIntoStore(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // pjacoco가 stop 시 쓸 <testId>.exec 를 흉내낸 fixture
        try (FileOutputStream out = new FileOutputStream(dir.resolve("T1.exec").toFile())) {
            ExecutionDataWriter w = new ExecutionDataWriter(out);
            w.visitClassExecution(new org.jacoco.core.data.ExecutionData(
                    0x1234L, "com/example/Foo", new boolean[]{true, false, true}));
        }
        PjacocoCoverageClient client = new PjacocoCoverageClient("127.0.0.1", 0, dir);
        ExecutionDataStore store = client.load("T1");
        assertThat(store.getContents()).anySatisfy(ed ->
            assertThat(ed.getName()).isEqualTo("com/example/Foo"));
    }
}
