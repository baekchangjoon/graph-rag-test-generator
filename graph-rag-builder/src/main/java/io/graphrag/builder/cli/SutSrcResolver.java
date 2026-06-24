package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** --sut-src 패턴 리스트(브레이스/리터럴/glob 혼용) → SourceRoots. REQ-001/003/004/009/017. */
public final class SutSrcResolver {

    private SutSrcResolver() {}

    public static SourceRoots resolve(String sutSrcArg, Path resourcesArg) {
        List<String> patterns = GlobToken.split(sutSrcArg);
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("--sut-src had no non-blank pattern");
        }
        Set<Path> roots = new LinkedHashSet<>();
        for (String pattern : patterns) {
            List<Path> matched = expand(pattern);
            if (matched.isEmpty()) {
                throw new IllegalArgumentException(
                        "--sut-src '" + pattern + "' matched no source directory");
            }
            roots.addAll(matched);
        }
        List<Path> sorted = new ArrayList<>(roots);
        sorted.sort(Comparator.naturalOrder());   // canonical 경로 안정 정렬(REQ-017)
        // primary 는 항상 첫 parse root(정렬 후) — 경로 파생/로그/단일 루트 sutSrc 환원용.
        // resourcesArg.getParent()를 쓰면 src/main 등 java 루트가 아닌 곳을 가리켜 위험(Gemini I3).
        return SourceRoots.of(sorted, sorted.get(0));
    }

    /** 패턴 1개 → 매칭 디렉터리(canonical). glob 메타 없으면 그 경로 자체(존재·디렉터리일 때). */
    private static List<Path> expand(String pattern) {
        if (!GlobMatcher.hasGlobMeta(pattern)) {
            Path p = Path.of(pattern);
            return Files.isDirectory(p) ? List.of(canonical(p)) : List.of();
        }
        // 절대·forward-slash 패턴으로 정규화 — NIO glob 은 항상 '/' 구분자를 쓰고,
        // Path.of(pattern).toString()은 Windows에서 '\'로 바꿔 glob을 깨뜨린다(Gemini I4).
        String absPattern = toAbsoluteGlob(pattern);
        Path base = globBase(absPattern);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + absPattern);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--sut-src malformed glob '" + pattern + "': " + e.getMessage(), e);
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isDirectory)
                // 매칭은 절대경로 문자열(forward-slash)로 — walk 결과를 절대화해 './x' 불일치 방지(Gemini I5)
                .filter(p -> matcher.matches(Path.of(p.toAbsolutePath().normalize().toString())))
                .forEach(p -> out.add(canonical(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 상대 패턴을 CWD 기준 절대 forward-slash glob 으로. 이미 절대면 그대로. */
    private static String toAbsoluteGlob(String pattern) {
        String fwd = pattern.replace('\\', '/');
        if (fwd.startsWith("/")) {
            return fwd;
        }
        String cwd = Path.of("").toAbsolutePath().toString().replace('\\', '/');
        return cwd + "/" + fwd;
    }

    /** 첫 glob 메타문자 이전의 최장 비-glob 접두 디렉터리(절대 패턴 기준). */
    private static Path globBase(String absPattern) {
        int meta = absPattern.length();
        for (int i = 0; i < absPattern.length(); i++) {
            char c = absPattern.charAt(i);
            if (c == '*' || c == '?' || c == '{' || c == '[') { meta = i; break; }
        }
        int slash = absPattern.lastIndexOf('/', meta);
        String basePart = slash > 0 ? absPattern.substring(0, slash) : "/";
        return Path.of(basePart);
    }

    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** resources 스캔 대상: --sut-resources 명시(있으면 1개) 또는 전 루트 sibling resources 중 존재분. REQ-011/019. */
    public static List<Path> resourceDirs(SourceRoots roots, Path resourcesArg) {
        if (resourcesArg != null) {
            return List.of(resourcesArg);
        }
        List<Path> out = new ArrayList<>();
        for (Path root : roots.parseRoots()) {
            Path res = root.resolveSibling("resources");
            if (Files.isDirectory(res)) {
                out.add(res);
            }
        }
        return out;
    }
}
