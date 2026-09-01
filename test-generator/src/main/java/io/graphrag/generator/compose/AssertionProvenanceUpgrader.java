package io.graphrag.generator.compose;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Outcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 합성된 응답 어설션 중 notNullValue()로 강등된 항목을, 추가 provenance로 결정성이 증명되는
 * 경우에 한해 구체 매처로 승격한다 (REQ-A/D — 2026-09-01 assertion-provenance 명세).
 *
 * <p>두 가지 증명 경로만 쓴다 — 관측값 스냅샷은 하지 않는다:
 * <ul>
 *   <li><b>REQ-A</b> 프레임워크 계약: Spring 기본 에러 엔벨로프({timestamp,status,error,path})의
 *       status==HTTP 상태코드, error==표준 reason phrase, path==요청 URI. 관측값이 계약 기대와
 *       일치할 때만 승격(커스텀 에러 핸들러 보호). FAILURE outcome 전용.</li>
 *   <li><b>REQ-D</b> 소스 리터럴: 관측 message가 핸들러 소스에서 추출된 예외 메시지 리터럴
 *       ({@link Endpoint#errorMessageLiterals()})과 일치(equalTo)하거나 조각을 포함(containsString).</li>
 * </ul>
 *
 * <p>기존 구체 매처는 절대 바꾸지 않으며 어설션을 추가하지도 않는다 — notNullValue() 승격만.
 *
 * <p>REQ-B(입력 배열 크기 유도 카운트)는 라이브 반례로 연기됐다: 배치 happy 시나리오의 count는
 * 입력뿐 아니라 DB 상태(참조 행 존재)에도 의존하는데, 생성 테스트가 그 행을 시드하지 않아
 * 탐색 관측값(1)과 런타임 값(0)이 갈렸다 — 요구사항명세의 REQ-B 연기 기록 참조.
 */
public final class AssertionProvenanceUpgrader {

    private static final String NOT_NULL = "notNullValue()";
    private static final String EQUAL_TO = "equalTo(";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_PATH = "path";

    /** Spring {@code HttpStatus.getReasonPhrase()} 값(기본 에러 엔벨로프의 error 필드가 쓰는 문구).
     *  RFC 9110의 개명(422 "Unprocessable Content" 등)과 다르다 — Spring 값으로 유지해야 한다. */
    private static final Map<Integer, String> REASON_PHRASES = Map.ofEntries(
            Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"),
            Map.entry(402, "Payment Required"), Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"),
            Map.entry(406, "Not Acceptable"), Map.entry(408, "Request Timeout"),
            Map.entry(409, "Conflict"), Map.entry(410, "Gone"),
            Map.entry(412, "Precondition Failed"), Map.entry(413, "Payload Too Large"),
            Map.entry(415, "Unsupported Media Type"), Map.entry(422, "Unprocessable Entity"),
            Map.entry(429, "Too Many Requests"), Map.entry(500, "Internal Server Error"),
            Map.entry(501, "Not Implemented"), Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"), Map.entry(504, "Gateway Timeout"));

    /** REQ-D 연결식 조각의 최소 길이 — 짧은 조각("a ", "id")의 우연 포함 오탐을 막는다.
     *  캡처부(ErrorMessageLiteralExtractor)의 동명 상수와 같아야 한다. */
    private static final int MIN_FRAGMENT_LENGTH = 8;

    /** path 어설션 승격 허용 문자(unreserved + '/') — 퍼센트 인코딩 불일치 방지. */
    private static final java.util.regex.Pattern URL_SAFE_PATH =
            java.util.regex.Pattern.compile("[A-Za-z0-9._~/-]*");

    private AssertionProvenanceUpgrader() {
    }

    /**
     * @param requestMutated 생성이 탐색 요청을 변형한 path(예: 404 read의 부재-id 센티널 치환).
     *                       이때 런타임이 타는 arm은 탐색 관측과 다를 수 있어, arm에 결합된 message
     *                       승격은 건너뛴다(요청 충실도가 message provenance의 전제). 엔벨로프
     *                       status/error/path는 arm 무관 프레임워크 계약이라 그대로 승격한다.
     */
    public static List<ComposedFixture.Assertion> upgrade(List<ComposedFixture.Assertion> assertions,
                                                          ExploredPath path, Endpoint endpoint,
                                                          String resolvedRequestPath,
                                                          boolean requestMutated) {
        JsonNode response = path.sampleResponse();
        if (response == null || !response.isObject()) {
            return assertions;
        }
        boolean failure = path.outcome() == Outcome.Kind.FAILURE;
        boolean envelope = failure && isSpringErrorEnvelope(response);

        List<ComposedFixture.Assertion> out = new ArrayList<>(assertions.size());
        for (ComposedFixture.Assertion a : assertions) {
            if (!NOT_NULL.equals(a.matcher())) {
                out.add(a);
                continue;
            }
            String upgraded = null;
            if (envelope) {
                upgraded = envelopeMatcher(a.jsonPath(), response, path.expectedStatus(),
                        endpoint.path(), resolvedRequestPath);
            }
            if (upgraded == null && failure && !requestMutated && "message".equals(a.jsonPath())) {
                upgraded = messageMatcher(response, endpoint.errorMessageLiterals());
            }
            out.add(upgraded == null ? a : new ComposedFixture.Assertion(a.jsonPath(), upgraded));
        }
        return out;
    }

    /** Spring 기본 에러 엔벨로프 형상인지 — 4필드가 모두 non-null로 존재해야 한다. */
    private static boolean isSpringErrorEnvelope(JsonNode response) {
        for (String f : new String[] {"timestamp", FIELD_STATUS, FIELD_ERROR, FIELD_PATH}) {
            JsonNode v = response.get(f);
            if (v == null || v.isNull()) {
                return false;
            }
        }
        return true;
    }

    private static String envelopeMatcher(String field, JsonNode response, int expectedStatus,
                                          String pathTemplate, String resolvedRequestPath) {
        switch (field) {
            case FIELD_STATUS -> {
                JsonNode v = response.get(FIELD_STATUS);
                if (v != null && v.isIntegralNumber() && v.asInt() == expectedStatus) {
                    return EQUAL_TO + expectedStatus + ")";
                }
            }
            case FIELD_ERROR -> {
                String phrase = REASON_PHRASES.get(expectedStatus);
                JsonNode v = response.get(FIELD_ERROR);
                if (phrase != null && v != null && v.isTextual() && phrase.equals(v.asText())) {
                    return EQUAL_TO + quote(phrase) + ")";
                }
            }
            case FIELD_PATH -> {
                JsonNode v = response.get(FIELD_PATH);
                // URL_SAFE 가드: path 변수 값에 공백·비ASCII가 있으면 클라이언트의 퍼센트 인코딩과
                // 서버 에코가 원본 문자열과 어긋난다 → unreserved 문자만일 때만 승격.
                if (v != null && v.isTextual() && resolvedRequestPath != null
                        && matchesTemplate(v.asText(), pathTemplate)
                        && URL_SAFE_PATH.matcher(stripQuery(resolvedRequestPath)).matches()) {
                    return EQUAL_TO + quote(stripQuery(resolvedRequestPath)) + ")";
                }
            }
            default -> {
                return null;
            }
        }
        return null;
    }

    /**
     * 관측 URI가 endpoint path 템플릿과 세그먼트 단위로 일치하는지 — 템플릿 변수({id})는
     * 와일드카드로 취급한다. 일치하면 "path 필드 == 요청 URI" 계약이 이 path에서 성립함이
     * 증명되어, 생성 시점의 실제 요청 경로로 equalTo 할 수 있다.
     */
    private static boolean matchesTemplate(String observedPath, String pathTemplate) {
        String[] observed = stripQuery(observedPath).split("/", -1);
        String[] template = stripQuery(pathTemplate).split("/", -1);
        if (observed.length != template.length) {
            return false;
        }
        for (int i = 0; i < template.length; i++) {
            boolean templateVar = template[i].startsWith("{") && template[i].endsWith("}");
            if (!templateVar && !template[i].equals(observed[i])) {
                return false;
            }
        }
        return true;
    }

    private static String messageMatcher(JsonNode response, List<String> literals) {
        JsonNode v = response.get("message");
        if (v == null || !v.isTextual() || literals.isEmpty()) {
            return null;
        }
        String observed = v.asText();
        if (literals.contains(observed)) {
            return EQUAL_TO + quote(observed) + ")";
        }
        String best = null;
        for (String fragment : literals) {
            if (fragment.length() >= MIN_FRAGMENT_LENGTH && observed.contains(fragment)
                    && (best == null || fragment.length() > best.length())) {
                best = fragment;
            }
        }
        return best == null ? null : "org.hamcrest.Matchers.containsString(" + quote(best) + ")";
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
