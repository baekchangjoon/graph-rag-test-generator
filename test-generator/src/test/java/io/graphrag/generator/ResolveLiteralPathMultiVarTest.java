package io.graphrag.generator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다중 path 변수 치환 가드 — builder의 buildPathAndQuery와 대칭(capture==reproduce).
 * 미바인딩 placeholder가 리터럴 {x}로 남아 생성 테스트 URL이 깨지던 회귀.
 */
class ResolveLiteralPathMultiVarTest {

    private static Endpoint get(String path, EndpointParam... params) {
        return new Endpoint("id", "GET", path, "C", "m", List.of(params), false);
    }

    @Test
    void twoBoundPathVars_bothSubstituted() {
        Endpoint ep = get("/a/b/c/{id}/{no}",
                new EndpointParam("id", "java.lang.Long", ParamKind.PATH),
                new EndpointParam("no", "java.lang.Long", ParamKind.PATH));
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("id", 5);
        input.put("no", 7);
        assertThat(Generator.resolveLiteralPath(ep, input, false)).isEqualTo("/a/b/c/5/7");
    }

    @Test
    void unboundSecondPlaceholder_sanitizedNotLeakedAsLiteralBraces() {
        // path엔 {id},{no} 둘 다 있으나 params엔 id만 PATH(no는 미캡처 — 헬퍼/클래스레벨 패턴).
        // builder는 buildPathAndQuery에서 sentinel("0")로 정리한다 → generator도 동일해야 한다.
        Endpoint ep = get("/a/b/c/{id}/{no}",
                new EndpointParam("id", "java.lang.Long", ParamKind.PATH));
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("id", 5);
        String url = Generator.resolveLiteralPath(ep, input, false);
        assertThat(url).doesNotContain("{");
        assertThat(url).isEqualTo("/a/b/c/5/0");
    }
}
