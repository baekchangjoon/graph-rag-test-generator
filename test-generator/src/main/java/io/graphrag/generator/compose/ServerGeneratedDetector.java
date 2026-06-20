package io.graphrag.generator.compose;

import java.util.regex.Pattern;

/**
 * UUID/ISO-8601 타임스탬프 등 서버 생성 값(매 요청 달라지는 비결정적 값)을 감지하는 유틸.
 * HTTP 경로(FixtureComposer)와 Kafka 페이로드 처리가 동일 로직을 공유하기 위해 추출.
 */
public final class ServerGeneratedDetector {

    static final String UUID_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    static final String TIMESTAMP_REGEX =
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}.*";

    private static final Pattern UUID_RE = Pattern.compile(UUID_REGEX);
    private static final Pattern TIMESTAMP_RE = Pattern.compile(TIMESTAMP_REGEX);

    private ServerGeneratedDetector() {
    }

    /**
     * UUID 또는 ISO-8601 타임스탬프처럼 매 요청 달라지는 서버 생성 값인지 여부를 반환한다.
     */
    public static boolean looksServerGenerated(String value) {
        return UUID_RE.matcher(value).matches() || TIMESTAMP_RE.matcher(value).matches();
    }

    /**
     * 값이 어떤 서버 생성 패턴에 해당하는지 반환한다.
     *
     * @return "UUID", "TIMESTAMP", 또는 해당 없으면 null
     */
    public static String patternType(String value) {
        if (UUID_RE.matcher(value).matches()) {
            return "UUID";
        }
        if (TIMESTAMP_RE.matcher(value).matches()) {
            return "TIMESTAMP";
        }
        return null;
    }

    /**
     * 주어진 타입명에 해당하는 Java 정규식 소스 문자열을 반환한다 (코드 생성용).
     *
     * @param type "UUID" 또는 "TIMESTAMP"
     * @return 해당 정규식 소스 문자열
     * @throws IllegalArgumentException 알 수 없는 타입인 경우
     */
    public static String regexFor(String type) {
        return switch (type) {
            case "UUID" -> UUID_REGEX;
            case "TIMESTAMP" -> TIMESTAMP_REGEX;
            default -> throw new IllegalArgumentException("Unknown pattern type: " + type);
        };
    }
}
