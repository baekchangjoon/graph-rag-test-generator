package io.graphrag.builder.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** REQ-009: CallSiteMatcher — method 일치 + segment-경계 endsWith + 최장 path 우선. */
class CallSiteMatcherTest {

    private static final Optional<BodyShape> SHAPE =
            Optional.of(new BodyShape("X", List.of(), false));

    private static ExternalCallSite site(String method, String path) {
        return new ExternalCallSite(method, path, SHAPE);
    }

    @Test
    void matchesByExactPathAndMethod() {
        var s = site("GET", "/inventory/stock");
        assertThat(CallSiteMatcher.match("GET", "/inventory/stock", List.of(s))).contains(s);
    }

    @Test
    void matchesWhenUrlHasBaseUrlPrefixSegment() {
        // 캡처 urlPath가 baseUrl path 구간을 앞에 달고 있어도 segment 경계 endsWith로 매칭
        var s = site("GET", "/inventory/stock");
        assertThat(CallSiteMatcher.match("GET", "/svc/inventory/stock", List.of(s))).contains(s);
    }

    @Test
    void doesNotMatchOnNonSegmentBoundarySuffix() {
        // "/stock"이 "restock"의 접미사여도 segment 경계가 아니면 매칭 금지
        var s = site("GET", "/stock");
        assertThat(CallSiteMatcher.match("GET", "/warehouse/restock", List.of(s))).isEmpty();
    }

    @Test
    void prefersLongerPathOnConflict() {
        var shortS = site("GET", "/b");
        var longS = site("GET", "/a/b");
        // 둘 다 "/x/a/b" 끝과 segment-매칭 가능하나 더 긴 "/a/b" 우선
        var matched = CallSiteMatcher.match("GET", "/x/a/b", List.of(shortS, longS));
        assertThat(matched).contains(longS);
    }

    @Test
    void methodMismatchNoMatch() {
        var s = site("POST", "/x");
        assertThat(CallSiteMatcher.match("GET", "/x", List.of(s))).isEmpty();
    }

    @Test
    void noCandidatesIsEmpty() {
        assertThat(CallSiteMatcher.match("GET", "/none", List.of())).isEmpty();
    }
}
