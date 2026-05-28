package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@link Endpoint}s from a Spring controller class.
 *
 * <p>Only annotation simple names are inspected — Symbol resolution is not
 * required, which keeps behavior predictable when the source tree is parsed
 * without external classpath jars.
 *
 * <p>Public entry point {@link #extract} skips non-controller classes (per
 * {@link ClassRoleClassifier#classify}). The lower-level
 * {@link #extractFromClass} bypasses the role check and is intended for unit
 * testing of the mapping logic in isolation.
 */
public final class EndpointExtractor {

    private static final Map<String, HttpMethod> SHORTHAND = Map.of(
            "GetMapping",    HttpMethod.GET,
            "PostMapping",   HttpMethod.POST,
            "PutMapping",    HttpMethod.PUT,
            "DeleteMapping", HttpMethod.DELETE,
            "PatchMapping",  HttpMethod.PATCH);

    private static final Set<String> AUTH_ANNOTATIONS =
            Set.of("PreAuthorize", "Secured", "RolesAllowed");

    private static final Pattern HAS_ROLE      = Pattern.compile("hasRole\\(['\"]([^'\"]+)['\"]\\)");
    private static final Pattern HAS_ANY_ROLE  = Pattern.compile("hasAnyRole\\(([^)]+)\\)");
    private static final Pattern ROLE_LITERAL  = Pattern.compile("['\"]([^'\"]+)['\"]");
    private static final String  IS_AUTHENTICATED = "isAuthenticated()";

    private record AuthInfo(boolean authRequired, List<String> roles) {
        static AuthInfo none() { return new AuthInfo(false, List.of()); }
    }

    private EndpointExtractor() {}

    public static List<Endpoint> extract(ClassOrInterfaceDeclaration cls,
                                         String classFqn, String project) {
        if (ClassRoleClassifier.classify(cls) != ClassRole.CONTROLLER) {
            return List.of();
        }
        return extractFromClass(cls, classFqn, project);
    }

    /** Visible for tests: applies endpoint-extraction logic ignoring the class role. */
    public static List<Endpoint> extractFromClass(ClassOrInterfaceDeclaration cls,
                                                  String classFqn, String project) {
        String basePath = classBasePath(cls);
        AuthInfo classAuth = readAuth(cls.getAnnotations());
        List<Endpoint> out = new ArrayList<>();
        for (MethodDeclaration m : cls.getMethods()) {
            Optional<Mapping> mapping = findMapping(m);
            if (mapping.isEmpty()) continue;
            String fullPath = normalize(basePath + normalize(mapping.get().path));
            if (fullPath.isEmpty()) fullPath = "/";
            AuthInfo methodAuth = readAuth(m.getAnnotations());
            AuthInfo merged = mergeAuth(classAuth, methodAuth);
            out.add(new Endpoint(
                    mapping.get().method + ":" + fullPath,
                    mapping.get().method,
                    fullPath,
                    project,
                    classFqn,
                    m.getNameAsString(),
                    merged.authRequired(),
                    merged.roles()));
        }
        return out;
    }

    private static AuthInfo mergeAuth(AuthInfo cls, AuthInfo method) {
        if (!cls.authRequired() && !method.authRequired()) return AuthInfo.none();
        // Method-level roles take precedence when present; class roles are the default.
        if (!method.roles().isEmpty()) return new AuthInfo(true, method.roles());
        if (!cls.roles().isEmpty())    return new AuthInfo(true, cls.roles());
        return new AuthInfo(true, List.of());
    }

    private static AuthInfo readAuth(NodeList<AnnotationExpr> annotations) {
        boolean required = false;
        List<String> roles = List.of();
        for (AnnotationExpr ann : annotations) {
            String name = ann.getNameAsString();
            if (!AUTH_ANNOTATIONS.contains(name)) continue;
            required = true;
            switch (name) {
                case "PreAuthorize" -> roles = parsePreAuthorizeRoles(rawSpel(ann));
                case "Secured"      -> roles = parseSecuredRoles(ann);
                case "RolesAllowed" -> roles = parseRolesAllowed(ann);
            }
            if (!roles.isEmpty()) break;     // first auth annotation with roles wins
        }
        return new AuthInfo(required, roles);
    }

    private static String rawSpel(AnnotationExpr ann) {
        if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr s) {
            return unquote(s.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    return unquote(pair.getValue().toString());
                }
            }
        }
        return "";
    }

    private static List<String> parsePreAuthorizeRoles(String spel) {
        if (spel.equals(IS_AUTHENTICATED)) return List.of();
        Matcher hr = HAS_ROLE.matcher(spel);
        if (hr.find()) return List.of(hr.group(1));
        Matcher har = HAS_ANY_ROLE.matcher(spel);
        if (har.find()) {
            List<String> roles = new ArrayList<>();
            Matcher lit = ROLE_LITERAL.matcher(har.group(1));
            while (lit.find()) roles.add(lit.group(1));
            return List.copyOf(roles);
        }
        return List.of();
    }

    private static List<String> parseSecuredRoles(AnnotationExpr ann) {
        List<String> raw = readStringArray(ann);
        List<String> stripped = new ArrayList<>(raw.size());
        for (String r : raw) {
            stripped.add(r.startsWith("ROLE_") ? r.substring("ROLE_".length()) : r);
        }
        return List.copyOf(stripped);
    }

    private static List<String> parseRolesAllowed(AnnotationExpr ann) {
        return readStringArray(ann);
    }

    private static List<String> readStringArray(AnnotationExpr ann) {
        String raw = "";
        if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr s) {
            raw = s.getMemberValue().toString();
        } else if (ann instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                if ("value".equals(pair.getNameAsString())) raw = pair.getValue().toString();
            }
        }
        if (raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = ROLE_LITERAL.matcher(raw);
        while (m.find()) out.add(m.group(1));
        return List.copyOf(out);
    }

    private static String classBasePath(ClassOrInterfaceDeclaration cls) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if ("RequestMapping".equals(ann.getNameAsString())) {
                String p = readPathAttribute(ann);
                if (!p.isEmpty()) return normalize(p);
            }
        }
        return "";
    }

    private static Optional<Mapping> findMapping(MethodDeclaration m) {
        for (AnnotationExpr ann : m.getAnnotations()) {
            String name = ann.getNameAsString();
            if (SHORTHAND.containsKey(name)) {
                return Optional.of(new Mapping(SHORTHAND.get(name), readPathAttribute(ann)));
            }
            if ("RequestMapping".equals(name) && ann instanceof NormalAnnotationExpr na) {
                Optional<HttpMethod> hm = readMethodAttribute(na);
                if (hm.isEmpty()) continue;       // RequestMapping without method= isn't a route
                return Optional.of(new Mapping(hm.get(), readPathAttribute(ann)));
            }
        }
        return Optional.empty();
    }

    /**
     * Read {@code value="..."} / {@code path="..."} / single-string element from any
     * mapping annotation form. Returns "" if not present. Only the first string is
     * honored — Spring's array form picks the first declared route.
     */
    private static String readPathAttribute(AnnotationExpr ann) {
        if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr s) {
            return unquote(s.getMemberValue().toString());
        }
        if (ann instanceof NormalAnnotationExpr n) {
            for (MemberValuePair pair : n.getPairs()) {
                String key = pair.getNameAsString();
                if ("value".equals(key) || "path".equals(key)) {
                    return firstStringValue(pair.getValue().toString());
                }
            }
        }
        return "";
    }

    private static Optional<HttpMethod> readMethodAttribute(NormalAnnotationExpr ann) {
        for (MemberValuePair pair : ann.getPairs()) {
            if (!"method".equals(pair.getNameAsString())) continue;
            String raw = pair.getValue().toString();
            int dot = raw.lastIndexOf('.');
            String tok = dot >= 0 ? raw.substring(dot + 1) : raw;
            try {
                return Optional.of(HttpMethod.valueOf(tok));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Extract the first quoted string from either a literal or a {@code {"a","b"}} array. */
    private static String firstStringValue(String raw) {
        String s = raw.trim();
        if (s.startsWith("{")) {
            int start = s.indexOf('"');
            int end = s.indexOf('"', start + 1);
            if (start < 0 || end < 0) return "";
            return s.substring(start + 1, end);
        }
        return unquote(s);
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        String r = s.startsWith("/") ? s : "/" + s;
        while (r.length() > 1 && r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private record Mapping(HttpMethod method, String path) {}
}
