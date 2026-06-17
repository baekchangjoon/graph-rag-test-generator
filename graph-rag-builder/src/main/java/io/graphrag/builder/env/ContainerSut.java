package io.graphrag.builder.env;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** attach 모드 SutHandle: 컨테이너 app의 published URL + `docker compose logs`가 append하는 로그 파일의 byte 슬라이스. */
public final class ContainerSut implements SutHandle {

    private final String baseUri;
    private final Path logFile;
    private final Process logTail;   // nullable (테스트). 환경이 소유한 `docker compose logs -f` 프로세스.

    public ContainerSut(String baseUri, Path logFile, Process logTail) {
        this.baseUri = baseUri;
        this.logFile = logFile;
        this.logTail = logTail;
    }

    @Override public String baseUri() { return baseUri; }

    @Override public long logOffset() {
        try { return Files.exists(logFile) ? Files.size(logFile) : 0; }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public String readLog() { return readLogRange(0, Long.MAX_VALUE); }
    @Override public String readLogFrom(long offset) { return readLogRange(offset, Long.MAX_VALUE); }

    @Override public String readLogRange(long start, long end) {
        return SutProcess.sliceUtf8(readBytes(), start, end);
    }

    private byte[] readBytes() {
        try { return Files.exists(logFile) ? Files.readAllBytes(logFile) : new byte[0]; }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public void stop() {
        if (logTail != null) { logTail.destroy(); }
    }
}
