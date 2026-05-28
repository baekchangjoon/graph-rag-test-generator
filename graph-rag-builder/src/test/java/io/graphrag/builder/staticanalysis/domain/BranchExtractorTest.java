package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BranchExtractorTest {

    @Test
    void if_statement_extracted_as_branch_with_condition_and_line() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int x) {
                    if (x > 0) { return 1; }
                    return 0;
                }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(1);
        Branch b = branches.get(0);
        assertThat(b.kind()).isEqualTo(BranchKind.IF);
        assertThat(b.condition()).isEqualTo("x > 0");
        assertThat(b.id()).startsWith("demo.C#m:line");
        assertThat(b.lineNumber()).isPositive();
        assertThat(b.referencedVariables()).containsExactly("x");
    }

    @Test
    void nested_if_surfaced_as_separate_branches() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int x, int y) {
                    if (x > 0) {
                        if (y < 0) { return -1; }
                    }
                    return 0;
                }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(2);
        assertThat(branches).extracting(Branch::condition).containsExactly("x > 0", "y < 0");
        // Determinism: ordered by line.
        assertThat(branches.get(0).lineNumber()).isLessThan(branches.get(1).lineNumber());
    }

    @Test
    void switch_statement_extracted_with_selector_as_condition() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int x) {
                    switch (x) {
                        case 1: return 1;
                        case 2: return 2;
                        default: return 0;
                    }
                }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).kind()).isEqualTo(BranchKind.SWITCH);
        assertThat(branches.get(0).condition()).isEqualTo("x");
    }

    @Test
    void ternary_expression_extracted() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int x) { return x > 0 ? 1 : -1; }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).kind()).isEqualTo(BranchKind.TERNARY);
        assertThat(branches.get(0).condition()).isEqualTo("x > 0");
    }

    @Test
    void throw_statement_extracted_with_empty_condition() {
        MethodDeclaration m = parseMethod("""
            class C {
                void m() { throw new RuntimeException("boom"); }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).kind()).isEqualTo(BranchKind.THROW);
        assertThat(branches.get(0).condition()).isEmpty();
    }

    @Test
    void referenced_variables_deduplicated_and_sorted() {
        MethodDeclaration m = parseMethod("""
            class C {
                int m(int a, int b) {
                    if (a + b > 0 && a < 100) { return 1; }
                    return 0;
                }
            }
            """);
        List<Branch> branches = BranchExtractor.extract(m, "demo.C");
        assertThat(branches.get(0).referencedVariables()).containsExactly("a", "b");
    }

    @Test
    void empty_method_yields_no_branches() {
        MethodDeclaration m = parseMethod("class C { void m() {} }");
        assertThat(BranchExtractor.extract(m, "demo.C")).isEmpty();
    }

    private static MethodDeclaration parseMethod(String src) {
        return StaticJavaParser.parse(src).findFirst(MethodDeclaration.class).orElseThrow();
    }
}
