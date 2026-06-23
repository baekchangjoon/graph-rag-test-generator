package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다중 path 변수 인덱싱: 캡처는 @PathVariable에만 의존하며 Swagger @ApiImplicitParam은 무시한다.
 * (다중 변수 중 2번째가 @PathVariable 없으면 미캡처 → URL 누출/센티널 폴백의 근본 원인.)
 */
class MultiPathVariableIndexingTest {

    private static Endpoint indexGet(java.nio.file.Path dir, String controller) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("C.java"), controller);
        return new EndpointIndexer().index(dir, null).endpoints().stream()
                .filter(e -> e.httpMethod().equals("GET")).findFirst().orElseThrow();
    }

    private static java.util.List<String> pathNames(Endpoint ep) {
        return ep.params().stream().filter(p -> p.kind() == ParamKind.PATH)
                .map(EndpointParam::name).toList();
    }

    @Test
    void bothPathVariable_withApiImplicitParams_bothCaptured(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // @ApiImplicitParams가 끼어 있어도 @PathVariable이 둘 다 있으면 둘 다 캡처(Swagger 무간섭).
        Endpoint ep = indexGet(dir, """
                package x;
                import org.springframework.web.bind.annotation.*;
                import io.swagger.annotations.*;
                @RestController
                class C {
                    @GetMapping(value = "/a/b/c/{d}/{e}")
                    @ApiImplicitParams({@ApiImplicitParam(name = "d"), @ApiImplicitParam(name = "e")})
                    public String abcDbyE(@PathVariable String d, @PathVariable String e) { return null; }
                }
                """);
        assertThat(pathNames(ep)).containsExactlyInAnyOrder("d", "e");
    }

    @Test
    void secondParamMissingPathVariable_onlyFirstCaptured(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // 근본 원인: 2번째 파라미터에 @PathVariable이 없으면 인덱서가 캡처하지 못해 {e}가 미바인딩으로 남는다.
        Endpoint ep = indexGet(dir, """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class C {
                    @GetMapping(value = "/a/b/c/{d}/{e}")
                    public String abcDbyE(@PathVariable String d, String e) { return null; }
                }
                """);
        assertThat(pathNames(ep)).containsExactly("d");
    }

    @Test
    void neitherPathVariable_onlySwagger_capturesNone(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // @ApiImplicitParam은 문서화 전용 — 인덱서/Spring 바인딩 모두 무시. @PathVariable 없으면 미캡처.
        Endpoint ep = indexGet(dir, """
                package x;
                import org.springframework.web.bind.annotation.*;
                import io.swagger.annotations.*;
                @RestController
                class C {
                    @GetMapping(value = "/a/b/c/{d}/{e}")
                    @ApiImplicitParams({@ApiImplicitParam(name = "d"), @ApiImplicitParam(name = "e")})
                    public String abcDbyE(String d, String e) { return null; }
                }
                """);
        assertThat(pathNames(ep)).isEmpty();
    }
}
