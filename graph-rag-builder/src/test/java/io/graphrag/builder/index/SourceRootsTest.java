package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceRootsTest {
    @Test
    void singleReducesToOneRoot() {
        SourceRoots r = SourceRoots.single(Path.of("/a/b"));
        assertEquals(List.of(Path.of("/a/b")), r.parseRoots());
        assertEquals(Path.of("/a/b"), r.primary());
        assertFalse(r.isMulti());
    }

    @Test
    void ofMultiFlagsMulti() {
        SourceRoots r = SourceRoots.of(List.of(Path.of("/a"), Path.of("/b")), Path.of("/a"));
        assertTrue(r.isMulti());
        assertEquals(Path.of("/a"), r.primary());
    }

    @Test
    void emptyRootsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceRoots.of(List.of(), Path.of("/a")));
    }
}
