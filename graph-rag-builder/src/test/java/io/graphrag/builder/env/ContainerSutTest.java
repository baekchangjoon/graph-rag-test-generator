package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSutTest {
    @Test
    void readsLogRangeByByteOffsetOverFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path log = dir.resolve("app.log");
        Files.writeString(log, "org.hibernate.SQL : select 1\n");
        ContainerSut sut = new ContainerSut("http://localhost:18080", log, null);
        long off = sut.logOffset();
        assertEquals(Files.size(log), off);
        Files.writeString(log, "binding parameter (1:INT) <- [7]\n",
                java.nio.file.StandardOpenOption.APPEND);
        String range = sut.readLogRange(off, sut.logOffset());
        assertTrue(range.contains("binding parameter (1:INT) <- [7]"));
        assertEquals("http://localhost:18080", sut.baseUri());
    }
}
