package io.graphrag.builder.coverage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * 번들된 pjacoco javaagent를 추출해 SUT 부착 옵션을 만든다.
 *
 * <p>OtelAgent.prepare 패턴을 따른다 — 빌드 시 리소스로 번들된 jar를 getResourceAsStream으로 추출.
 * pjacoco agent 옵션: destfile(dir), port, includes, traceKeyAutoCreate.
 * agent 부착 순서: OTel → pjacoco (OTel span이 먼저 생성돼야 traceId가 전파됨).
 */
public final class PjacocoAgent {

    private final Path agentJar;

    private PjacocoAgent(Path agentJar) {
        this.agentJar = agentJar;
    }

    public static PjacocoAgent prepare(Path workDir) {
        try (InputStream in = PjacocoAgent.class.getResourceAsStream("/agents/pjacoco-agent.jar")) {
            if (in == null) {
                throw new IllegalStateException("bundled pjacoco-agent.jar not found in resources");
            }
            Path jar = workDir.resolve("pjacoco-agent.jar");
            Files.createDirectories(workDir);
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
            return new PjacocoAgent(jar);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to prepare pjacoco agent", e);
        }
    }

    public Path agentJar() {
        return agentJar;
    }

    /**
     * 호스트 프로세스(컨테이너 밖) SUT 부착용 JAVA_TOOL_OPTIONS.
     *
     * @param destfileDir  pjacoco가 &lt;traceId&gt;.exec 를 기록하는 디렉터리 (절대 경로)
     * @param controlPort  HTTP 제어 엔드포인트 포트 (/{@code __coverage__}/test/stop 등)
     * @param includes     SUT 패키지 glob (예: {@code io.example.*}). "**\/*" 또는 null 이면
     *                     sutSrc 에서 자동 탐지한 패키지를 사용한다.
     * @param sutSrc       SUT Java 소스 루트 (includes 자동 탐지용; null 가능)
     * @param traceKeyAutoCreate OTel traceId 기반 자동 store 생성 여부 (기본 true)
     */
    public String javaToolOptions(Path destfileDir, int controlPort, String includes, Path sutSrc, boolean traceKeyAutoCreate) {
        return buildOptions(
                "-javaagent:" + agentJar.toAbsolutePath(),
                destfileDir.toAbsolutePath().toString(),
                controlPort,
                includes,
                detectRootPackage(sutSrc),
                traceKeyAutoCreate,
                null);   // 로컬(호스트 프로세스) SUT: 제어 서버는 기본 loopback(127.0.0.1) 바인드로 충분.
    }

    /**
     * 컨테이너 내부 SUT 부착용 JAVA_TOOL_OPTIONS.
     *
     * @param mountPath    컨테이너 안에서 바라보는 agent jar 경로 (volume mount 대상)
     * @param destfileDir  컨테이너 안에서 바라보는 destfile 디렉터리 경로
     */
    public String containerJavaToolOptions(String mountPath, Path destfileDir,
                                           int controlPort, String includes, Path sutSrc, boolean traceKeyAutoCreate) {
        // 컨테이너 SUT: 제어 서버를 0.0.0.0 에 바인드해야 Docker published 포트(host→container)가 도달한다.
        // 기본값(127.0.0.1)이면 컨테이너 loopback에만 listen → published 포트 forward가 EOF/refused.
        return buildOptions("-javaagent:" + mountPath, destfileDir.toString(), controlPort, includes,
                detectRootPackage(sutSrc), traceKeyAutoCreate, "0.0.0.0");
    }

    /**
     * SUT 소스 루트에서 앱 코드의 **최장 공통 패키지 prefix**를 탐지한다. 단일 자식 디렉터리
     * 체인을 따라 내려가며, .java 파일이 있는 디렉터리(=실제 코드 패키지) 또는 분기점(자식 ≥2)에서 멈춘다.
     *
     * <p>예: {@code src/main/java/io/graphrag/sample/orders} → {@code "io.graphrag.sample.orders.*"}.
     *
     * <p><b>왜 최상위 한 세그먼트({@code io.*})로는 안 되는가</b>: pjacoco는 includes glob에
     * 매칭되는 클래스를 로드 시 계측한다. {@code io.*}는 SUT 앱뿐 아니라 OTel javaagent의
     * span-export 클래스({@code io.opentelemetry.*})와 {@code io.netty.*} 등 서드파티까지 계측
     * 대상에 넣는다. 그 결과 병렬 실행에서 OTel BSP export hot-path가 계측 오버헤드로 starve되어
     * SQL entry-span이 timeout된다(P2-5 근본원인). 앱 패키지로 좁히면 OTel/서드파티는 계측되지 않는다.
     * 발견 실패 시 null 반환(호출자가 폴백 처리).
     */
    public static String detectRootPackage(Path sutSrc) {
        if (sutSrc == null || !Files.isDirectory(sutSrc)) return null;
        java.util.List<String> segments = new java.util.ArrayList<>();
        Path current = sutSrc;
        // 단일 자식 디렉터리 체인을 하강. .java 파일이 나오거나 분기(자식 0/≥2)면 멈춘다.
        while (true) {
            boolean hasJava;
            java.util.List<Path> subDirs;
            try (Stream<Path> entries = Files.list(current)) {
                java.util.List<Path> all = entries.toList();
                hasJava = all.stream().anyMatch(p ->
                        Files.isRegularFile(p) && p.getFileName().toString().endsWith(".java"));
                subDirs = all.stream()
                        .filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .toList();
            } catch (IOException e) {
                return null;
            }
            // 현재 디렉터리에 코드가 있거나 단일 자식이 아니면 여기서 멈춘다.
            if (hasJava || subDirs.size() != 1) {
                break;
            }
            segments.add(subDirs.get(0).getFileName().toString());
            current = subDirs.get(0);
        }
        if (segments.isEmpty()) {
            return null;
        }
        return String.join(".", segments) + ".*";
    }

    /**
     * JaCoCo 스타일 glob("**\/*" 포함)을 pjacoco WildcardMatcher 형식으로 정규화한다.
     * <ul>
     *   <li>"**\/*" 또는 null/blank → sutSrc 에서 최상위 패키지를 자동 탐지 ("io.*" 등).
     *       OTel javaagent와 공존 시 "*" 는 JDK proxy 모듈 접근 오류를 유발하므로 금지.</li>
     *   <li>그 외는 그대로 사용 (예: "io.graphrag.*")</li>
     * </ul>
     */
    static String normalizeIncludes(String includes, String detectedRoot) {
        if (includes == null || includes.isBlank() || "**/*".equals(includes)) {
            // "*" 은 JDK proxy 계층(jdk.proxy4 모듈) 계측 시 IllegalAccessError 유발 → 탐지된 패키지 우선
            if (detectedRoot != null) {
                return detectedRoot;
            }
            throw new IllegalStateException(
                    "pjacoco includes: SUT 루트 패키지를 탐지하지 못했습니다. "
                    + "--sut-pkg <패키지글로브> (예: io.example.*) 를 명시하세요. "
                    + "\"*\" 로 폴백하면 JDK proxy 계층 계측 시 IllegalAccessError 가 발생합니다.");
        }
        return includes;
    }

    private static String buildOptions(String agentArg, String destDir, int port,
                                       String includes, String detectedRoot, boolean traceKeyAutoCreate,
                                       String controlBindAddress) {
        StringBuilder sb = new StringBuilder(agentArg)
                .append("=destfile=").append(destDir)
                .append(",port=").append(port)
                .append(",includes=").append(normalizeIncludes(includes, detectedRoot));
        if (controlBindAddress != null) {
            // pjacoco AgentOptions: "address" 키가 제어 HTTP 서버 바인드 호스트(기본 127.0.0.1).
            sb.append(",address=").append(controlBindAddress);
        }
        if (traceKeyAutoCreate) {
            sb.append(",traceKeyAutoCreate=true");
        }
        return sb.toString();
    }
}
