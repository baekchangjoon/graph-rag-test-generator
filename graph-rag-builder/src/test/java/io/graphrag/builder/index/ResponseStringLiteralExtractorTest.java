package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResponseStringLiteralExtractor 단위 테스트 (REQ-007, REQ-003).
 *
 * <p>sample-src OrderController에 아래 패턴이 추가돼 있음:
 * <ol>
 *   <li>"EMBARGOED".equals(stock.region())  — 기본 equals(리터럴.equals(accessor))</li>
 *   <li>stock.region().equalsIgnoreCase("X1") — equalsIgnoreCase</li>
 *   <li>Objects.equals(stock.region(), "X2") — Objects.equals</li>
 *   <li>String r = stock.region(); "X3".equals(r) — 로컬 변수 바인딩</li>
 *   <li>CONST_REGION.equals(stock.region())  — static final String CONST_REGION = "X4"</li>
 *   <li>stock.region().startsWith("EMBARGO") — 비동치(loud skip, 결과 미포함)</li>
 * </ol>
 */
class ResponseStringLiteralExtractorTest {

    /** InventoryResponse의 responseShape: available(int), mode(enum), region(String). */
    private static final BodyShape INVENTORY_SHAPE = new BodyShape(
            "io.graphrag.sample.orders.InventoryClient$InventoryResponse",
            List.of(
                    new BodyShape.BodyField("available", "int"),
                    new BodyShape.BodyField("mode", "io.graphrag.sample.orders.FulfillmentMode"),
                    new BodyShape.BodyField("region", "java.lang.String")
            )
    );

    private CtModel sampleModel() {
        Launcher l = new Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        l.addInputResource("src/test/resources/sample-src");
        return l.buildModel();
    }

    // -------------------------------------------------------------------------
    // 테스트 1: 기본 equals(리터럴 → accessor 방향) 추출 — "EMBARGOED"
    // -------------------------------------------------------------------------

    @Test
    void extractsRegionEqualsLiteral() {
        CtModel model = sampleModel();
        var site = new ExternalCallSite("GET", "/inventory/stock", Optional.of(INVENTORY_SHAPE));

        Map<String, Map<String, List<String>>> out =
                new ResponseStringLiteralExtractor().extract(model, List.of(site));

        assertThat(out).containsKey(INVENTORY_SHAPE.javaType());
        List<String> regionLiterals = out.get(INVENTORY_SHAPE.javaType()).get("region");
        assertThat(regionLiterals).contains("EMBARGOED");
    }

    // -------------------------------------------------------------------------
    // 테스트 2: 모든 equals-family 패턴 + 로컬 바인딩 + static-final
    // -------------------------------------------------------------------------

    @Test
    void extractsAllEqualsFamilyPatternsAndLocalBindingAndConst() {
        CtModel model = sampleModel();
        var site = new ExternalCallSite("GET", "/inventory/stock", Optional.of(INVENTORY_SHAPE));

        Map<String, Map<String, List<String>>> out =
                new ResponseStringLiteralExtractor().extract(model, List.of(site));

        List<String> regionLiterals = out.get(INVENTORY_SHAPE.javaType()).get("region");

        // equals(리터럴.equals(accessor)), equalsIgnoreCase, Objects.equals,
        // 로컬바인딩("X3"), static final 상수("X4") 모두 추출돼야 한다.
        // TreeSet → 알파벳 정렬: EMBARGOED < X1 < X2 < X3 < X4
        assertThat(regionLiterals).containsExactly("EMBARGOED", "X1", "X2", "X3", "X4");
    }

    // -------------------------------------------------------------------------
    // 테스트 3: startsWith → 결과에 미포함(비동치 loud-skip)
    // -------------------------------------------------------------------------

    @Test
    void startWithsIsNotIncludedInResult() {
        CtModel model = sampleModel();
        var site = new ExternalCallSite("GET", "/inventory/stock", Optional.of(INVENTORY_SHAPE));

        Map<String, Map<String, List<String>>> out =
                new ResponseStringLiteralExtractor().extract(model, List.of(site));

        List<String> regionLiterals = out.get(INVENTORY_SHAPE.javaType()).get("region");
        // startsWith의 인자 "EMBARGO"는 결과에 없어야 한다.
        assertThat(regionLiterals).doesNotContain("EMBARGO");
    }

    // -------------------------------------------------------------------------
    // 테스트 4: startsWith 시 string-literal-nonequality-skipped loud-log 발생
    // -------------------------------------------------------------------------

    @Test
    void nonequalityLoudLogFiredForStartsWith() {
        CtModel model = sampleModel();
        var site = new ExternalCallSite("GET", "/inventory/stock", Optional.of(INVENTORY_SHAPE));

        Logger extractorLog = Logger.getLogger(ResponseStringLiteralExtractor.class.getName());
        CapturingHandler handler = new CapturingHandler();
        extractorLog.addHandler(handler);
        try {
            new ResponseStringLiteralExtractor().extract(model, List.of(site));
        } finally {
            extractorLog.removeHandler(handler);
        }

        assertThat(handler.messages)
                .anyMatch(m -> m.contains("string-literal-nonequality-skipped")
                        && m.contains("startsWith"));
    }

    // -------------------------------------------------------------------------
    // 테스트 5: 동명 String 필드를 가진 DTO 2개 → ambiguous loud-skip, 결과 미포함
    // -------------------------------------------------------------------------

    @Test
    void ambiguousFieldAcrossTwoDtosIsSkipped() {
        CtModel model = sampleModel();

        // InventoryResponse에 region(String) — 이미 있음
        var site1 = new ExternalCallSite("GET", "/inventory/stock", Optional.of(INVENTORY_SHAPE));

        // 두 번째 DTO에도 region(String) 필드를 추가 → ambiguous
        BodyShape otherShape = new BodyShape(
                "io.graphrag.sample.orders.SomeOtherResponse",
                List.of(new BodyShape.BodyField("region", "java.lang.String"))
        );
        var site2 = new ExternalCallSite("GET", "/other/endpoint", Optional.of(otherShape));

        Logger extractorLog = Logger.getLogger(ResponseStringLiteralExtractor.class.getName());
        CapturingHandler handler = new CapturingHandler();
        extractorLog.addHandler(handler);
        Map<String, Map<String, List<String>>> out;
        try {
            out = new ResponseStringLiteralExtractor().extract(model, List.of(site1, site2));
        } finally {
            extractorLog.removeHandler(handler);
        }

        // ambiguous → region이 어느 DTO 버킷에도 들어가지 않음
        assertThat(out).doesNotContainKey(INVENTORY_SHAPE.javaType());
        assertThat(out).doesNotContainKey(otherShape.javaType());

        // loud-log 발생 확인
        assertThat(handler.messages)
                .anyMatch(m -> m.contains("string-literal-accessor-ambiguous")
                        && m.contains("region"));
    }

    // -------------------------------------------------------------------------
    // 테스트 6: responseShape가 없는 callSite → 결과 비어 있음
    // -------------------------------------------------------------------------

    @Test
    void emptyResponseShapeProducesNoResults() {
        CtModel model = sampleModel();
        var site = new ExternalCallSite("GET", "/unknown", Optional.empty());

        Map<String, Map<String, List<String>>> out =
                new ResponseStringLiteralExtractor().extract(model, List.of(site));

        assertThat(out).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 내부 헬퍼: JUL 로그 캡처 핸들러
    // -------------------------------------------------------------------------

    private static class CapturingHandler extends Handler {
        final java.util.List<String> messages = new java.util.ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getMessage() != null) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
