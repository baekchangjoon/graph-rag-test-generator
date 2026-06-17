package io.graphrag.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 헤더 값 템플릿: {{now:<java.time 패턴>}} 를 요청 시각(Asia/Seoul)으로 치환, 나머지 리터럴 보존. */
public final class HeaderTemplate {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern NOW = Pattern.compile("\\{\\{now:([^}]+)}}");

    private HeaderTemplate() {}

    public static String resolve(String template, Instant now) {
        Matcher m = NOW.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String formatted = DateTimeFormatter.ofPattern(m.group(1)).withZone(SEOUL).format(now);
            m.appendReplacement(out, Matcher.quoteReplacement(formatted));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** fail-fast: 모든 {{now:pattern}} 의 pattern 이 유효한 java.time 패턴인지 검증(시작 시 1회). */
    public static void validate(String template) {
        Matcher m = NOW.matcher(template);
        while (m.find()) {
            DateTimeFormatter.ofPattern(m.group(1));   // 잘못된 패턴이면 IllegalArgumentException
        }
    }
}
