package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.model.CapturedHttpCall;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * span-발견 {@link EgressCall}을 인덱싱한 {@link ExternalCallSite}에 매칭해 형상-시드 응답 body를
 * 합성한다 (REQ-S015-001/003, REQ-F012-001/002/010/011). 순수 함수 — 상태·로깅 없음.
 * 로깅·수집은 호출자(captureHttpCalls)가 한다.
 */
final class EgressStubComposer {

    private EgressStubComposer() {
    }

    /** 합성 결과: 성공이면 형상 JSON·SYNTHESIZED/CONTRACT, 실패면 ""·CAPTURED + 사유 loudFail. */
    record Outcome(String responseBody,
                   CapturedHttpCall.Provenance provenance,
                   Optional<EndpointExplorationRunner.LoudFail> loudFail) {
    }

    /**
     * 형상-시드 body 합성 + String 리터럴 덮어쓰기 (REQ-F012-001/002/010/011).
     *
     * @param stringLiteralsByDto dtoFqn→field→리터럴 목록; 첫 리터럴이 String 필드에 덮어쓰인다.
     *                            리터럴이 1건이라도 적용되면 provenance=CONTRACT, 없으면 SYNTHESIZED.
     */
    static Outcome compose(EgressCall e, List<ExternalCallSite> callSites, ShapeJsonSynthesizer shapes,
                           Map<String, Map<String, List<String>>> stringLiteralsByDto) {
        Optional<ExternalCallSite> site = CallSiteMatcher.match(e.method(), e.path(), callSites);
        if (site.isEmpty()) {
            return fail("unmatched-external-call", e.method() + " " + e.path());
        }
        Optional<BodyShape> shape = site.get().responseShape();
        if (shape.isEmpty()) {
            return fail("unwired-external-dep", e.method() + " " + e.path());
        }
        try {
            JsonNode body = shapes.synthesizeBody(shape.get());
            boolean contract = applyExpectedStringLiterals(body, shape.get(), stringLiteralsByDto);
            return new Outcome(body.toString(),
                    contract ? CapturedHttpCall.Provenance.CONTRACT : CapturedHttpCall.Provenance.SYNTHESIZED,
                    Optional.empty());
        } catch (ShapeJsonSynthesizer.UnsupportedShapeException ex) {
            return fail("unsynthesizable-shape", site.get().pathLiteral());
        }
    }

    /**
     * body의 String 필드를 javaType→field→리터럴(첫 값)로 덮어쓴다.
     * 1건이라도 적용하면 true(CONTRACT), 적용 없으면 false(SYNTHESIZED) (REQ-F012-010 silent-fallback).
     */
    private static boolean applyExpectedStringLiterals(JsonNode body, BodyShape shape,
            Map<String, Map<String, List<String>>> stringLiteralsByDto) {
        if (!(body instanceof ObjectNode obj)) return false;
        Map<String, List<String>> byField = stringLiteralsByDto.getOrDefault(shape.javaType(), Map.of());
        boolean applied = false;
        for (var entry : byField.entrySet()) {
            List<String> lits = entry.getValue();
            if (lits != null && !lits.isEmpty() && obj.has(entry.getKey()) && obj.get(entry.getKey()).isTextual()) {
                obj.put(entry.getKey(), lits.get(0));   // 결정적: 첫 리터럴
                applied = true;
            }
        }
        return applied;
    }

    private static Outcome fail(String reason, String target) {
        return new Outcome("", CapturedHttpCall.Provenance.CAPTURED,
                Optional.of(new EndpointExplorationRunner.LoudFail(reason, target)));
    }
}
