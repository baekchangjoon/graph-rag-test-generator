package io.graphrag.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.index.BodyShape;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Spoon이 해석하지 못한 @RequestBody 타입을 Instancio 리플렉션으로 인스턴스화하여
 * BodyShape + happyTemplate(JsonNode)를 생성하는 폴백 전략.
 *
 * <p>#1 규칙: GRACEFUL DEGRADATION — 모든 실패(클래스로딩, Instancio, 직렬화)는
 * Optional.empty()를 반환하고 절대 빌드를 중단시키지 않는다.
 */
public class ReflectiveBodyInstantiator {

    private static final Logger log = LoggerFactory.getLogger(ReflectiveBodyInstantiator.class);

    /** 위험한 Jackson 커스텀 직렬화 어노테이션 descriptor — 이 클래스는 건드리지 않는다. */
    private static final List<String> DANGEROUS_JACKSON_DESCS = List.of(
            "Lcom/fasterxml/jackson/databind/annotation/JsonSerialize;",
            "Lcom/fasterxml/jackson/databind/annotation/JsonDeserialize;",
            "Lcom/fasterxml/jackson/annotation/JsonTypeInfo;"
    );

    /** BOOT-INF/lib 총합 500 MB 초과 시 지나치게 큰 fat jar로 간주하여 처리 중단. */
    private static final long MAX_LIB_SIZE_BYTES = 500L * 1024 * 1024;

    /** Instancio가 생성한 인스턴스의 직렬화 결과. */
    public record ReflectiveBody(BodyShape shape, JsonNode happyTemplate) {
    }

    private final boolean enabled;
    private final ObjectMapper mapper;

    public ReflectiveBodyInstantiator(boolean enabled) {
        this.enabled = enabled;
        this.mapper = new ObjectMapper();
    }

    /**
     * 주어진 FQN 타입을 SUT jar에서 로드하여 Instancio로 인스턴스화하고
     * BodyShape + happyTemplate을 반환한다.
     *
     * <p>비활성화되어 있거나 어떠한 단계에서든 실패하면 Optional.empty()를 반환한다.
     */
    public Optional<ReflectiveBody> resolve(String fqn, Path sutJar) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return doResolve(fqn, sutJar);
        } catch (Throwable t) {
            log.debug("ReflectiveBodyInstantiator: resolve failed for {} — {}", fqn, t.toString());
            return Optional.empty();
        }
    }

    private Optional<ReflectiveBody> doResolve(String fqn, Path sutJar) throws Exception {
        URL[] urls = buildClasspath(sutJar);
        if (urls == null) {
            return Optional.empty();
        }

        try (URLClassLoader child = new URLClassLoader(urls, getClass().getClassLoader())) {
            // 커스텀 Jackson 어노테이션 가드 — 잘못된 JSON 생성 차단
            if (hasCustomJacksonAnnotations(fqn, child)) {
                log.debug("ReflectiveBodyInstantiator: {} has custom Jackson annotations — skipping", fqn);
                return Optional.empty();
            }

            ClassLoader original = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(child);
                Class<?> clazz = child.loadClass(fqn);
                Object instance = org.instancio.Instancio.of(clazz)
                        .withSeed((long) fqn.hashCode())
                        .create();
                JsonNode tree = mapper.valueToTree(instance);
                List<BodyShape.BodyField> fields = deriveFields("", tree);
                BodyShape shape = new BodyShape(fqn, fields);
                return Optional.of(new ReflectiveBody(shape, tree));
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Classpath 구성
    // -------------------------------------------------------------------------

    private URL[] buildClasspath(Path sutJar) throws Exception {
        boolean isFatJar = false;
        try (ZipFile zf = new ZipFile(sutJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().startsWith("BOOT-INF/")) {
                    isFatJar = true;
                    break;
                }
            }
        }

        if (isFatJar) {
            return buildFatJarClasspath(sutJar);
        } else {
            return new URL[]{sutJar.toUri().toURL()};
        }
    }

    private URL[] buildFatJarClasspath(Path sutJar) throws Exception {
        String cacheKey = sha256Key(sutJar);
        Path extractDir = Path.of(System.getProperty("java.io.tmpdir"), "grb-instancio", cacheKey);
        Path doneMarker = extractDir.resolve(".done");

        if (!Files.exists(doneMarker)) {
            long libSize = 0;
            try (ZipFile zf = new ZipFile(sutJar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().startsWith("BOOT-INF/lib/") && e.getName().endsWith(".jar")) {
                        libSize += e.getSize();
                    }
                }
            }
            if (libSize > MAX_LIB_SIZE_BYTES) {
                log.debug("ReflectiveBodyInstantiator: fat jar lib size {}MB > 500MB limit — skipping",
                        libSize / (1024 * 1024));
                return null;
            }

            Files.createDirectories(extractDir);
            Path classesDir = extractDir.resolve("classes");
            Path libDir = extractDir.resolve("lib");
            Files.createDirectories(classesDir);
            Files.createDirectories(libDir);

            try (ZipFile zf = new ZipFile(sutJar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (e.isDirectory()) {
                        continue;
                    }
                    if (name.startsWith("BOOT-INF/classes/")) {
                        String rel = name.substring("BOOT-INF/classes/".length());
                        Path target = classesDir.resolve(rel);
                        Files.createDirectories(target.getParent());
                        try (InputStream in = zf.getInputStream(e)) {
                            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                        String jarName = name.substring(name.lastIndexOf('/') + 1);
                        Path target = libDir.resolve(jarName);
                        try (InputStream in = zf.getInputStream(e)) {
                            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            Files.createFile(doneMarker);

            final Path toDelete = extractDir;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(toDelete)));
        }

        Path classesDir = extractDir.resolve("classes");
        Path libDir = extractDir.resolve("lib");

        List<URL> urls = new ArrayList<>();
        urls.add(classesDir.toUri().toURL());
        File[] libs = libDir.toFile().listFiles(f -> f.getName().endsWith(".jar"));
        if (libs != null) {
            for (File lib : libs) {
                urls.add(lib.toURI().toURL());
            }
        }
        return urls.toArray(new URL[0]);
    }

    private String sha256Key(Path sutJar) throws Exception {
        String input = sutJar.toString() + Files.size(sutJar);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, 16);
    }

    private void deleteQuietly(Path dir) {
        try {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // 커스텀 Jackson 어노테이션 ASM 스캔
    // -------------------------------------------------------------------------

    private boolean hasCustomJacksonAnnotations(String fqn, ClassLoader cl) {
        String resourcePath = fqn.replace('.', '/') + ".class";
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return false;
            }
            byte[] bytes = readAllBytes(in);
            ClassReader cr = new ClassReader(bytes);
            boolean[] found = {false};
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (DANGEROUS_JACKSON_DESCS.contains(descriptor)) {
                        found[0] = true;
                    }
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                        String signature, Object value) {
                    return new FieldVisitor(Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                            if (DANGEROUS_JACKSON_DESCS.contains(annDesc)) {
                                found[0] = true;
                            }
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0];
        } catch (Throwable t) {
            log.debug("ReflectiveBodyInstantiator: ASM scan failed for {} — {}", fqn, t.toString());
            return false;
        }
    }

    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    // -------------------------------------------------------------------------
    // JsonNode 트리 → BodyShape 필드 유도
    // -------------------------------------------------------------------------

    private List<BodyShape.BodyField> deriveFields(String prefix, JsonNode node) {
        List<BodyShape.BodyField> result = new ArrayList<>();
        collectFields(prefix, node, result);
        return result;
    }

    private void collectFields(String prefix, JsonNode node, List<BodyShape.BodyField> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                collectFields(path, entry.getValue(), out);
            }
        } else if (node.isArray()) {
            if (node.size() > 0) {
                collectFields(prefix, node.get(0), out);
            } else {
                out.add(new BodyShape.BodyField(prefix, "java.util.List"));
            }
        } else {
            String javaType = inferJavaType(node);
            out.add(new BodyShape.BodyField(prefix, javaType));
        }
    }

    private String inferJavaType(JsonNode node) {
        if (node.isTextual()) return "java.lang.String";
        if (node.isBoolean()) return "java.lang.Boolean";
        if (node.isLong() || node.isIntegralNumber()) return "java.lang.Integer";
        if (node.isDouble() || node.isFloat() || node.isFloatingPointNumber()) return "java.lang.Double";
        if (node.isBigDecimal()) return "java.math.BigDecimal";
        if (node.isNull()) return "java.lang.Object";
        return "java.lang.Object";
    }
}
