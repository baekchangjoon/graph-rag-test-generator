package io.graphrag.builder.cli;

import java.util.ArrayList;
import java.util.List;

/** brace-aware CSV 토큰화: brace 깊이 0의 콤마에서만 분리(`{a,b}` 보존). REQ-018. */
public final class GlobToken {

    private GlobToken() {}

    public static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '{') {
                depth++;
                cur.append(c);
            } else if (c == '}') {
                if (depth > 0) { depth--; }
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                addStripped(out, cur);
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        addStripped(out, cur);
        return out;
    }

    private static void addStripped(List<String> out, StringBuilder sb) {
        String t = sb.toString().strip();
        if (!t.isEmpty()) {
            out.add(t);
        }
    }
}
