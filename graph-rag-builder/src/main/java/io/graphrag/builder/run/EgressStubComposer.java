package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.model.CapturedHttpCall;

import java.util.List;
import java.util.Optional;

/**
 * span-발견 {@link EgressCall}을 인덱싱한 {@link ExternalCallSite}에 매칭해 형상-시드 응답 body를
 * 합성한다 (REQ-S015-001/003). 순수 함수 — 상태·로깅 없음. 로깅·수집은 호출자(captureHttpCalls)가 한다.
 */
final class EgressStubComposer {

    private EgressStubComposer() {
    }

    /** 합성 결과: 성공이면 형상 JSON·SYNTHESIZED, 실패면 ""·CAPTURED + 사유 loudFail. */
    record Outcome(String responseBody,
                   CapturedHttpCall.Provenance provenance,
                   Optional<EndpointExplorationRunner.LoudFail> loudFail) {
    }

    static Outcome compose(EgressCall e, List<ExternalCallSite> callSites, ShapeJsonSynthesizer shapes) {
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
            return new Outcome(body.toString(), CapturedHttpCall.Provenance.SYNTHESIZED, Optional.empty());
        } catch (ShapeJsonSynthesizer.UnsupportedShapeException ex) {
            return fail("unsynthesizable-shape", site.get().pathLiteral());
        }
    }

    private static Outcome fail(String reason, String target) {
        return new Outcome("", CapturedHttpCall.Provenance.CAPTURED,
                Optional.of(new EndpointExplorationRunner.LoudFail(reason, target)));
    }
}
