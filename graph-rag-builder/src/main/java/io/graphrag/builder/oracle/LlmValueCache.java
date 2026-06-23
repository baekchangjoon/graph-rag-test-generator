package io.graphrag.builder.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.index.BodyShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * LLM 값 결정성 캐시. 읽기는 classpath(/llm-oracle-cache/&lt;key&gt;.json) — 빌드 JAR에서도 동작,
 * 쓰기는 소스트리 파일시스템(개발자가 커밋). 키에 핸들러 본문을 포함해 바디 변경 시 자동 무효화.
 */
public final class LlmValueCache {
    private static final Logger log = LoggerFactory.getLogger(LlmValueCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CP_PREFIX = "/llm-oracle-cache/";
    private final Path writeDir;

    public LlmValueCache(Path writeDir) {
        this.writeDir = writeDir;
    }

    /**
     * 읽기는 classpath로 어디서든 동작. 쓰기 경로는 CWD에서 위로 올라가며 모듈 소스트리
     * (graph-rag-builder/src/main/resources)를 탐색해 결정한다 — repo 루트에서 실행하든
     * gradle :run 처럼 모듈 디렉터리에서 실행하든 같은 경로로 수렴(이중경로/write 실패 방지).
     */
    public static LlmValueCache defaultClasspath() {
        return new LlmValueCache(resolveWriteDir());
    }

    private static Path resolveWriteDir() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            // repo 루트에서 실행: <root>/graph-rag-builder/src/main/resources
            Path fromRoot = dir.resolve("graph-rag-builder/src/main/resources");
            if (Files.isDirectory(fromRoot)) {
                return fromRoot.resolve("llm-oracle-cache");
            }
            // 모듈 디렉터리에서 실행(gradle :run): <root>/graph-rag-builder/src/main/resources
            if (dir.getFileName() != null && dir.getFileName().toString().equals("graph-rag-builder")
                    && Files.isDirectory(dir.resolve("src/main/resources"))) {
                return dir.resolve("src/main/resources/llm-oracle-cache");
            }
        }
        return Path.of("graph-rag-builder/src/main/resources/llm-oracle-cache");
    }

    /**
     * sha256(endpoint.id + 핸들러 본문 + 정렬 필드셋(name:type) + modelId). 핸들러 본문은 Spoon
     * 직렬화 텍스트(주석 포함)이므로 본문 내 주석 변경도 키를 바꾼다(재질의 유발) — 의도된 보수적
     * 무효화(REQ-003: 본문 변경 시 캐시 무효).
     */
    public static String key(String endpointId, String handlerSource,
                             List<BodyShape.BodyField> fields, String modelId) {
        TreeSet<String> sorted = new TreeSet<>();
        for (BodyShape.BodyField f : fields) {
            sorted.add(f.name() + ":" + f.javaType());
        }
        String canonical = endpointId + "\n" + handlerSource + "\n"
                + String.join(",", sorted) + "\n" + modelId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** classpath(빌드/CI) 우선, 없으면 writeDir 파일시스템(로컬 dev — 재빌드 없이 갓 쓴 캐시 읽기). */
    public Optional<LlmFieldValues> read(String key) {
        try (InputStream in = LlmValueCache.class.getResourceAsStream(CP_PREFIX + key + ".json")) {
            if (in != null) {
                return Optional.of(MAPPER.readValue(in, LlmFieldValues.class));
            }
        } catch (Exception e) {
            log.warn("llm cache classpath read failed for key {}: {}", key, e.getMessage());
        }
        try {
            Path file = writeDir.resolve(key + ".json");
            if (Files.exists(file)) {
                return Optional.of(MAPPER.readValue(Files.readString(file), LlmFieldValues.class));
            }
        } catch (Exception e) {
            log.warn("llm cache fs read failed for key {}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    public void write(String key, LlmFieldValues values) {
        try {
            Files.createDirectories(writeDir);
            Files.writeString(writeDir.resolve(key + ".json"), MAPPER.writeValueAsString(values));
        } catch (Exception e) {
            log.warn("llm cache write failed for key {} (kept value, uncached): {}",
                    key, e.getMessage());
        }
    }
}
