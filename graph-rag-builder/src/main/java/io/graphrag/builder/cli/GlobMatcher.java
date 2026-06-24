package io.graphrag.builder.cli;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 문자열 glob→regex 매처(플랫폼 독립). NIO PathMatcher 미사용 — 대상이 파일시스템 경로가
 * 아니라 endpoint id / "METHOD /path" 문자열이고 OS 구분자에 무관해야 하기 때문. REQ-009.
 * 문법: *=세그먼트 내([^/]*), **=/ 횡단(.*), ?=[^/], {a,b}=(a|b), [abc]=문자클래스.
 */
public final class GlobMatcher {

    private GlobMatcher() {}

    public static boolean hasGlobMeta(String spec) {
        return spec.indexOf('*') >= 0 || spec.indexOf('?') >= 0
                || spec.indexOf('{') >= 0 || spec.indexOf('[') >= 0;
    }

    public static boolean matches(String glob, String text) {
        Pattern p = compile(glob);
        return p.matcher(text).matches();
    }

    private static Pattern compile(String glob) {
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        re.append(".*");
                        i++;
                    } else {
                        re.append("[^/]*");
                    }
                    break;
                case '?':
                    re.append("[^/]");
                    break;
                case '{':
                    re.append('(');
                    break;
                case '}':
                    re.append(')');
                    break;
                case ',':
                    re.append('|');
                    break;
                case '[':
                    re.append('[');   // 문자 클래스 시작 — 그대로 전달
                    break;
                case ']':
                    re.append(']');
                    break;
                // 정규식 특수문자 이스케이프
                case '.': case '(': case ')': case '+': case '^':
                case '$': case '|': case '\\':
                    re.append('\\').append(c);
                    break;
                default:
                    re.append(c);
            }
            i++;
        }
        re.append('$');
        try {
            return Pattern.compile(re.toString());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "malformed glob '" + glob + "': " + e.getDescription(), e);
        }
    }
}
