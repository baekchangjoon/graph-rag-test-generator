package io.graphrag.translator;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands a Spring-style URI template like {@code /api/owners/{ownerId}/pets/{petId}}
 * into a concrete URI, substituting {@code path-param} placeholders and appending
 * query parameters in sorted order so the output is deterministic.
 *
 * <p>This is pure (no I/O, no SPI), so it can be unit-tested without touching disk.
 *
 * <p>URL-encoding rules used here:
 * <ul>
 *   <li>Path segments: RFC-3986 percent-encoding (spaces → {@code %20}).</li>
 *   <li>Query values: form-urlencoding ({@link URLEncoder}, so spaces → {@code +}).</li>
 * </ul>
 * Both choices match what {@code HttpClient.send} sends on the wire.
 */
public final class PathTemplateExpander {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^/{}]+)}");

    private PathTemplateExpander() {}

    public static String expand(String template,
                                Map<String, String> pathParams,
                                Map<String, String> queryParams) {
        String path = substitutePathParams(template, pathParams);
        return appendQueryParams(path, queryParams);
    }

    private static String substitutePathParams(String template, Map<String, String> pathParams) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = pathParams.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                        "path-param '" + key + "' is unbound for template " + template);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(encodePathSegment(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String appendQueryParams(String path, Map<String, String> queryParams) {
        if (queryParams.isEmpty()) return path;
        // Sort by key so different Map implementations produce identical config.yml output.
        StringBuilder out = new StringBuilder(path).append('?');
        boolean first = true;
        for (var entry : new TreeMap<>(queryParams).entrySet()) {
            if (!first) out.append('&');
            out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            out.append('=');
            out.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return out.toString();
    }

    /**
     * Percent-encodes a path segment. Unlike {@link URLEncoder} we keep the unreserved
     * set as-is and encode spaces as {@code %20} (path segments must not contain {@code +}).
     */
    private static String encodePathSegment(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isUnreservedPathChar(c)) {
                out.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%');
                    out.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                    out.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
        }
        return out.toString();
    }

    private static boolean isUnreservedPathChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
