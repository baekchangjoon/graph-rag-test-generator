package io.graphrag.builder.capture.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.HttpClientType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WireMock 서버가 받은 요청을 {@link CapturedHttpCall}로 변환.
 *
 * <p>분석 환경에서 외부 시스템을 WireMock으로 대체한 뒤, SUT가 호출한 요청들을 회수.
 * 클라이언트 타입은 정확히 알 수 없어 OTHER로 기본 설정 (Phase 2+에서 stack trace 등으로 보강).
 */
public final class WireMockHttpRecorder {

    private final WireMockServer server;

    public WireMockHttpRecorder(WireMockServer server) {
        this.server = server;
    }

    /** WireMock이 받은 모든 요청을 CapturedHttpCall로 변환. */
    public List<CapturedHttpCall> captureAll(String pathId, String targetExternalId) {
        List<ServeEvent> events = server.getAllServeEvents();
        List<CapturedHttpCall> result = new ArrayList<>(events.size());
        // ServeEvents는 최신 우선 정렬되어 옴 — 들어온 순서로 뒤집어 보존
        for (int i = events.size() - 1; i >= 0; i--) {
            ServeEvent event = events.get(i);
            result.add(toCaptured(event, pathId, targetExternalId));
        }
        return result;
    }

    /** 활성 CaptureContext가 있으면 거기로 결과를 흘림. 없으면 noop. */
    public void captureIntoContext(String targetExternalId) {
        CaptureContext ctx = CaptureContext.current();
        if (ctx == null) return;
        for (CapturedHttpCall call : captureAll(ctx.pathId(), targetExternalId)) {
            ctx.addCapturedHttpCall(call);
        }
    }

    private static CapturedHttpCall toCaptured(ServeEvent event, String pathId, String targetId) {
        LoggedRequest req = event.getRequest();
        String url = req.getUrl() == null ? "" : req.getUrl();
        int status = event.getResponse() == null ? 0 : event.getResponse().getStatus();
        Object responseBody = event.getResponse() == null ? null
                : event.getResponse().getBodyAsString();

        Map<String, String> headers = new HashMap<>();
        if (req.getHeaders() != null) {
            req.getHeaders().all().forEach(h -> headers.put(h.key(), h.firstValue()));
        }

        Object requestBody = req.getBodyAsString();

        return new CapturedHttpCall(
                "h-" + UUID.randomUUID(),
                pathId,
                req.getMethod() == null ? "GET" : req.getMethod().value(),
                url, url, List.of(),
                headers, requestBody, List.of(),
                status, responseBody, List.of(),
                HttpClientType.OTHER,
                targetId);
    }
}
