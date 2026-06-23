package io.graphrag.builder.run;

import io.graphrag.builder.index.ExternalCallSite;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 캡처된 외부 호출(method, urlPath)을 인덱싱한 {@link ExternalCallSite}와 매칭한다 (REQ-009).
 *
 * <p>캡처 urlPath는 baseUrl 정규화 후 path 부분만 남으므로, 인덱싱 pathLiteral과
 * <b>segment 경계 기준 endsWith</b>로 비교한다. 복수 매치는 pathLiteral이 가장 긴 것을
 * 우선(specificity)하고, 길이 동률이면 첫 매치를 쓰고 WARN을 남긴다. method도 일치해야 한다.
 */
public final class CallSiteMatcher {

    private static final Logger log = LoggerFactory.getLogger(CallSiteMatcher.class);

    private CallSiteMatcher() {
    }

    public static Optional<ExternalCallSite> match(String method, String urlPath,
            List<ExternalCallSite> sites) {
        ExternalCallSite best = null;
        boolean ambiguous = false;
        for (ExternalCallSite site : sites) {
            if (!method.equalsIgnoreCase(site.httpMethod())) {
                continue;
            }
            if (!pathMatches(urlPath, site.pathLiteral())) {
                continue;
            }
            if (best == null || site.pathLiteral().length() > best.pathLiteral().length()) {
                best = site;
                ambiguous = false;
            } else if (site.pathLiteral().length() == best.pathLiteral().length()
                    && !site.pathLiteral().equals(best.pathLiteral())) {
                ambiguous = true;
            }
        }
        if (ambiguous) {
            log.warn("ambiguous-call-site-match: method={} urlPath={} chose pathLiteral={}",
                    method, urlPath, best.pathLiteral());
        }
        return Optional.ofNullable(best);
    }

    /**
     * urlPath가 pathLiteral과 segment 경계로 끝나는지. pathLiteral은 '/'로 시작하므로
     * {@code urlPath.endsWith(pathLiteral)}이면 매치 시작 직전이 '/' 경계임이 보장된다.
     */
    private static boolean pathMatches(String urlPath, String pathLiteral) {
        if (urlPath.equals(pathLiteral)) {
            return true;
        }
        if (pathLiteral.startsWith("/") && urlPath.endsWith(pathLiteral)) {
            return true;
        }
        // 방어: pathLiteral이 '/'로 시작하지 않으면 명시적으로 '/' 경계를 요구
        return urlPath.endsWith("/" + pathLiteral);
    }
}
