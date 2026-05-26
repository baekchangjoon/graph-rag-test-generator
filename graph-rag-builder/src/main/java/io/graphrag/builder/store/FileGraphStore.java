package io.graphrag.builder.store;

import io.graphrag.builder.persistence.GraphArchive;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Phase 0-5 기본 구현: 파일 기반 {@link GraphArchive} 위에 {@link GraphStore} 인터페이스 제공.
 *
 * <p>save 호출마다 디스크에 flush. 자주 flush가 비효율이면 caller가 batch + 명시적 flush 호출.
 */
public final class FileGraphStore implements GraphStore {

    private final GraphArchive archive;
    private final Path baseDir;

    public FileGraphStore(Path baseDir) {
        this.baseDir = baseDir;
        try {
            this.archive = GraphArchive.load(baseDir);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public FileGraphStore(GraphArchive archive, Path baseDir) {
        this.archive = archive;
        this.baseDir = baseDir;
    }

    @Override
    public void saveEndpoint(Endpoint endpoint) {
        archive.addEndpoint(endpoint);
        flush();
    }

    @Override
    public Optional<Endpoint> findEndpoint(String id) {
        return archive.findEndpoint(id);
    }

    @Override
    public List<Endpoint> allEndpoints() {
        return archive.endpoints();
    }

    @Override
    public void savePath(ExploredPath path) {
        archive.addExploredPath(path);
        flush();
    }

    @Override
    public Optional<ExploredPath> findPath(String id) {
        return archive.findPath(id);
    }

    @Override
    public List<ExploredPath> pathsByEndpoint(String endpointId) {
        return archive.pathsByEndpoint(endpointId);
    }

    @Override
    public void saveCapturedSql(CapturedSql sql) {
        archive.addCapturedSql(sql);
        flush();
    }

    @Override
    public List<CapturedSql> capturedSqlByPath(String pathId) {
        return archive.capturedSqlByPath(pathId);
    }

    @Override
    public void saveCapturedHttpCall(CapturedHttpCall call) {
        archive.addCapturedHttpCall(call);
        flush();
    }

    @Override
    public List<CapturedHttpCall> capturedHttpByPath(String pathId) {
        return archive.capturedHttpByPath(pathId);
    }

    private void flush() {
        try { archive.save(); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    public Path baseDir() { return baseDir; }
}
