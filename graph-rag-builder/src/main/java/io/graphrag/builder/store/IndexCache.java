package io.graphrag.builder.store;

import io.graphrag.builder.index.SourceRoots;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 정적 인덱싱 whole-result 캐시(manifest.json + static-index.json). */
public final class IndexCache {

    public static final int SCHEMA_VERSION = 3;
    private static final String MANIFEST = "manifest.json";
    private static final String INDEX = "static-index.json";

    private IndexCache() {
    }

    public static IndexManifest scan(Path sutSrc, Path sutResources) {
        return scan(sutSrc, sutResources, null);
    }

    public static IndexManifest scan(Path sutSrc, Path sutResources, AuthConfig authConfig) {
        Map<String, IndexManifest.FileEntry> files = new LinkedHashMap<>();
        collect(sutSrc, ".java", "sutSrc", files);
        collect(sutResources, ".xml", "sutResources", files);
        String authFingerprint = buildAuthFingerprint(authConfig);
        return new IndexManifest(SCHEMA_VERSION, authFingerprint, files);
    }

    public static IndexManifest scan(SourceRoots roots, List<Path> resourceDirs, AuthConfig authConfig) {
        Map<String, IndexManifest.FileEntry> files = new LinkedHashMap<>();
        List<Path> parseRoots = roots.parseRoots();
        for (int i = 0; i < parseRoots.size(); i++) {
            collect(parseRoots.get(i), ".java", "sutSrc#" + i, files);
        }
        for (int i = 0; i < resourceDirs.size(); i++) {
            collect(resourceDirs.get(i), ".xml", "sutResources#" + i, files);
        }
        return new IndexManifest(SCHEMA_VERSION, buildAuthFingerprint(authConfig), files);
    }

    private static String buildAuthFingerprint(AuthConfig authConfig) {
        if (authConfig == null) {
            return "";
        }
        String loginPath = authConfig.loginPath() != null ? authConfig.loginPath() : "";
        String publicPaths = authConfig.publicPaths().stream()
                .sorted()
                .collect(Collectors.joining("\0"));  // NUL: 경로에 절대 포함 불가
        return "loginPath=" + loginPath + "\0\0publicPaths=" + publicPaths;  // \0\0: 두 섹션 구분
    }

    private static void collect(Path root, String ext, String label,
                                Map<String, IndexManifest.FileEntry> out) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(ext)).sorted().forEach(p -> {
                String rel = label + "/" + root.relativize(p).toString().replace('\\', '/');
                out.put(rel, new IndexManifest.FileEntry(label, sha256(p)));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isFresh(IndexManifest cached, IndexManifest current) {
        return cached != null
                && cached.schemaVersion() == current.schemaVersion()
                && cached.authFingerprint().equals(current.authFingerprint())
                && cached.files().equals(current.files());
    }

    public static Optional<StaticIndex> load(Path cacheDir, IndexManifest current) {
        Path manifestPath = cacheDir.resolve(MANIFEST);
        Path indexPath = cacheDir.resolve(INDEX);
        if (!Files.exists(manifestPath) || !Files.exists(indexPath)) {
            return Optional.empty();
        }
        try {
            IndexManifest cached = Json.mapper().readValue(manifestPath.toFile(), IndexManifest.class);
            if (!isFresh(cached, current)) {
                return Optional.empty();
            }
            return Optional.of(Json.mapper().readValue(indexPath.toFile(), StaticIndex.class));
        } catch (IOException e) {
            return Optional.empty();   // 손상 → 풀 리빌드 (REQ-010)
        }
    }

    public static void save(Path cacheDir, IndexManifest manifest, StaticIndex index) {
        try {
            Files.createDirectories(cacheDir);
            writeAtomic(cacheDir.resolve(INDEX), Json.mapper().writeValueAsBytes(index));
            writeAtomic(cacheDir.resolve(MANIFEST), Json.mapper().writeValueAsBytes(manifest));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
