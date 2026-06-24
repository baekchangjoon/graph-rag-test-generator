package io.graphrag.builder.capture.egress;
import io.graphrag.builder.capture.otlp.SpanRecord;
import java.net.URI; import java.util.Map; import java.util.Optional;
public final class EgressNormalizer {
    private EgressNormalizer() {}
    public static Optional<EgressCall> fromSpan(SpanRecord s) {
        if (!isClient(s.kind())) return Optional.empty();
        Map<String,String> a = s.attributes();
        String method = firstNonNull(a.get("http.request.method"), a.get("http.method"));
        if (method == null || method.isBlank()) return Optional.empty();
        String path = extractPath(a);
        if (path == null || path.isBlank()) return Optional.empty();
        Integer status = parseIntOrNull(firstNonNull(a.get("http.response.status_code"), a.get("http.status_code")));
        return Optional.of(new EgressCall(method, path, status, s.traceId(), s.startUnixNano()));
    }
    private static boolean isClient(String k){ return "SPAN_KIND_CLIENT".equals(k) || "CLIENT".equals(k); }
    private static String extractPath(Map<String,String> a){
        String p = firstNonNull(a.get("url.path"), a.get("http.target"), a.get("http.path"));
        if (p != null) return stripQuery(p);
        String full = firstNonNull(a.get("url.full"), a.get("http.url"));
        if (full == null) return null;
        try {
            String stripped = stripQuery(full);
            // Remove scheme prefix (e.g., "http://") if present
            int schemeEnd = stripped.indexOf("://");
            if (schemeEnd != -1) {
                stripped = stripped.substring(schemeEnd + 3);
            }
            // Treat remaining as path, ensuring it starts with /
            if (stripped.isEmpty()) return null;
            return stripped.startsWith("/") ? stripped : "/" + stripped;
        } catch (RuntimeException e) { return stripQuery(full); }
    }
    private static String stripQuery(String s){ if (s==null) return null; int q=s.indexOf('?'); return q<0?s:s.substring(0,q); }
    private static String firstNonNull(String... v){ for (String x:v) if (x!=null) return x; return null; }
    private static Integer parseIntOrNull(String v){ if (v==null||v.isBlank()) return null;
        try { return Integer.valueOf(v.trim()); } catch (NumberFormatException e){ return null; } }
}
