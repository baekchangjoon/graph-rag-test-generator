package io.graphrag.builder.index;

import io.graphrag.builder.oracle.HandlerSourceExtractor;
import io.graphrag.builder.oracle.InputOracle;
import io.graphrag.builder.oracle.StaticLiteralOracle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.CtModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5: 탐색 단계 Spoon 모델 단일 공유. CtModel 오버로드가 모델을 재빌드하지 않고(O(1)),
 * SourceRoots 오버로드와 facts가 동등함을 직접 입증한다.
 */
class SharedModelReuseTest {

    private static final String HANDLER = "h.H";
    private static final String CMD = "h.Cmd";

    /** 비교/문자열동치/conjunction/enum컬럼/stateguard/validation/literal이 모두 비지 않도록 충분히 풍부한 fixture. */
    private Path fixture(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Path h = Files.createDirectories(src.resolve("h"));
        Files.writeString(h.resolve("H.java"), """
                package h;
                public class H {
                    public void create(Cmd c) {
                        if (c.getQuantity() > 41) {}
                        if (c.getName().equals("EXPRESS")) {}
                        if (c.getQuantity() > 0 && c.getName().equals("VIP")) {}
                        if (c.getStatus() == Status.ACTIVE) {}
                    }
                    public void update(Cmd c) {
                        if (c.getPrice() >= 10) {}
                    }
                }
                """);
        Files.writeString(h.resolve("Cmd.java"), """
                package h;
                import jakarta.validation.constraints.NotNull;
                import jakarta.validation.constraints.Min;
                public class Cmd {
                    @NotNull private String name;
                    @Min(1) private Integer quantity;
                    @Min(0) private Integer price;
                    private Status status;
                    public String getName() { return name; }
                    public Integer getQuantity() { return quantity; }
                    public Integer getPrice() { return price; }
                    public Status getStatus() { return status; }
                }
                """);
        Files.writeString(h.resolve("Status.java"), """
                package h;
                public enum Status { ACTIVE, INACTIVE }
                """);
        return src;
    }

    /** explore 정적 분석이 호출하는 CtModel 오버로드 전부 — 모델 1회 빌드 외 추가 빌드 0. */
    private void callAllCtModelOverloads(CtModel model) {
        ConstraintExtractor ce = new ConstraintExtractor();
        ce.extractComparisons(model);
        ce.extractStringEqualities(model);
        ce.extractConjunctions(model);
        ce.extractJoinGuards(model);
        ce.extractEnumColumns(model);
        ce.extractStateGuards(model);
        ce.extractStateGuardConjunctions(model);
        // 여러 엔드포인트(핸들러 메서드)만큼 반복해도 추가 빌드가 없어야 한다.
        ce.extract(model, HANDLER, "create");
        ce.extract(model, HANDLER, "update");
        ce.reachableMethods(model, HANDLER, "create");
        ce.reachableMethods(model, HANDLER, "update");
        new LiteralCandidateExtractor().extract(model, HANDLER);
        new ValidationConstraintExtractor().extract(model, CMD);
    }

    @Test
    void ctModelOverloadsBuildModelOnce(@TempDir Path tmp) throws Exception {
        SourceRoots roots = SourceRoots.single(fixture(tmp));
        SharedSpoonModel.resetBuildCount();
        CtModel model = SharedSpoonModel.build(roots);   // 유일한 빌드

        callAllCtModelOverloads(model);

        assertThat(SharedSpoonModel.buildCount())
                .as("CtModel 오버로드는 모델을 재빌드하지 않아야 — 초기 1회뿐")
                .isEqualTo(1);
    }

    @Test
    void sourceRootsOverloadsRebuildEachCall_beforeBaseline(@TempDir Path tmp) throws Exception {
        SourceRoots roots = SourceRoots.single(fixture(tmp));
        ConstraintExtractor ce = new ConstraintExtractor();
        SharedSpoonModel.resetBuildCount();

        // 리팩터 전 동작 입증: SourceRoots 오버로드는 호출마다 재빌드(O(N)).
        ce.extractComparisons(roots);
        ce.extractStringEqualities(roots);
        ce.extractConjunctions(roots);
        ce.extractJoinGuards(roots);
        ce.extractEnumColumns(roots);
        ce.extractStateGuards(roots);
        ce.extractStateGuardConjunctions(roots);
        ce.extract(roots, HANDLER, "create");
        ce.extract(roots, HANDLER, "update");
        ce.reachableMethods(roots, HANDLER, "create");
        ce.reachableMethods(roots, HANDLER, "update");
        new LiteralCandidateExtractor().extract(roots, HANDLER);
        new ValidationConstraintExtractor().extract(roots, CMD);

        assertThat(SharedSpoonModel.buildCount())
                .as("SourceRoots 오버로드는 호출 수만큼 빌드(O(N)) — 공유 모델의 before 대조")
                .isEqualTo(13);
    }

    @Test
    void ctModelAndSourceRootsProduceEquivalentFacts(@TempDir Path tmp) throws Exception {
        SourceRoots roots = SourceRoots.single(fixture(tmp));
        CtModel model = SharedSpoonModel.build(roots);
        ConstraintExtractor ce = new ConstraintExtractor();

        assertThat(ce.extractComparisons(model)).isEqualTo(ce.extractComparisons(roots));
        assertThat(ce.extractStringEqualities(model)).isEqualTo(ce.extractStringEqualities(roots));
        assertThat(ce.extractConjunctions(model)).isEqualTo(ce.extractConjunctions(roots));
        assertThat(ce.extractJoinGuards(model)).isEqualTo(ce.extractJoinGuards(roots));
        assertThat(ce.extractEnumColumns(model)).isEqualTo(ce.extractEnumColumns(roots));
        assertThat(ce.extractStateGuards(model)).isEqualTo(ce.extractStateGuards(roots));
        assertThat(ce.extractStateGuardConjunctions(model))
                .isEqualTo(ce.extractStateGuardConjunctions(roots));
        assertThat(ce.extract(model, HANDLER, "create")).isEqualTo(ce.extract(roots, HANDLER, "create"));
        assertThat(ce.reachableMethods(model, HANDLER, "create"))
                .isEqualTo(ce.reachableMethods(roots, HANDLER, "create"));
        assertThat(new LiteralCandidateExtractor().extract(model, HANDLER))
                .isEqualTo(new LiteralCandidateExtractor().extract(roots, HANDLER));
        assertThat(new ValidationConstraintExtractor().extract(model, CMD))
                .isEqualTo(new ValidationConstraintExtractor().extract(roots, CMD));

        // fixture가 실제로 비어있지 않은지(동등성이 empty==empty의 공허한 통과가 아님) 확인
        assertThat(ce.extractComparisons(model)).isNotEmpty();
        assertThat(ce.extractStringEqualities(model)).isNotEmpty();
        assertThat(new ValidationConstraintExtractor().extract(model, CMD)).isNotEmpty();
    }

    @Test
    void multiRootCtModelIncludesNonPrimaryHandler(@TempDir Path tmp) throws Exception {
        Path primary = Files.createDirectories(tmp.resolve("feature"));
        Path nonPrimary = Files.createDirectories(tmp.resolve("common"));
        Files.writeString(Files.createDirectories(primary.resolve("f")).resolve("F.java"),
                "package f;\npublic class F { public void a() {} }");
        Files.writeString(Files.createDirectories(nonPrimary.resolve("c")).resolve("C.java"),
                "package c;\npublic class C { public void g(int q) { if (q > 41) {} } }");

        CtModel model = SharedSpoonModel.build(SourceRoots.of(List.of(primary, nonPrimary), primary));
        List<ConstraintExtractor.Comparison> comps = new ConstraintExtractor().extractComparisons(model);

        assertThat(comps)
                .as("공유 모델은 전 parseRoots를 포함 — 비-primary 루트(C.g)의 q>41 비교가 누락되면 안 됨")
                .anyMatch(c -> c.literal() == 41L && c.fieldRef().equals("q"));
    }

    @Test
    void handlerSourceStableAfterSharedModelTraversal(@TempDir Path tmp) throws Exception {
        // 공유 모델 위험: 여러 추출기가 같은 모델을 traverse하면 Spoon이 lazy 참조 해소로 상태를
        // 변형해 핸들러 본문 toString이 달라질 수 있다 → LLM 캐시 키(sha256(... handlerSource ...))가
        // 어긋난다. 본문이 traverse 전후 동일함을 가드한다.
        SourceRoots roots = SourceRoots.single(fixture(tmp));
        String freshBody = new HandlerSourceExtractor(SharedSpoonModel.build(roots))
                .extract(HANDLER, "create");

        CtModel shared = SharedSpoonModel.build(roots);
        callAllCtModelOverloads(shared);   // 탐색 단계처럼 전 추출기로 traverse
        String sharedBody = new HandlerSourceExtractor(shared).extract(HANDLER, "create");

        assertThat(sharedBody)
                .as("traverse된 공유 모델의 핸들러 본문이 fresh 모델과 동일해야 LLM 캐시 키 보존")
                .isEqualTo(freshBody);
    }

    @Test
    void staticLiteralOracleReusesInjectedModel(@TempDir Path tmp) throws Exception {
        SourceRoots roots = SourceRoots.single(fixture(tmp));
        SharedSpoonModel.resetBuildCount();
        CtModel model = SharedSpoonModel.build(roots);   // 유일한 빌드

        InputOracle.SutCode sut = new InputOracle.SutCode(roots, tmp.resolve("unused.jar"));
        new StaticLiteralOracle(model).analyze(sut);

        assertThat(SharedSpoonModel.buildCount())
                .as("StaticLiteralOracle은 주입된 공유 모델을 재사용 — 추가 빌드 0")
                .isEqualTo(1);
    }

    @Test
    void staticLiteralOracleNoArgFallbackEquivalentToInjected(@TempDir Path tmp) throws Exception {
        // 하위호환: 무인자 생성자(모델 null → sut.roots()로 빌드) 경로가 주입 경로와 동일 후보를 낸다.
        SourceRoots roots = SourceRoots.single(fixture(tmp));
        InputOracle.SutCode sut = new InputOracle.SutCode(roots, tmp.resolve("unused.jar"));

        var injected = new StaticLiteralOracle(SharedSpoonModel.build(roots)).analyze(sut);
        var fallback = new StaticLiteralOracle().analyze(sut);

        assertThat(fallback.numeric()).isEqualTo(injected.numeric());
        assertThat(fallback.strings()).isEqualTo(injected.strings());
    }
}
