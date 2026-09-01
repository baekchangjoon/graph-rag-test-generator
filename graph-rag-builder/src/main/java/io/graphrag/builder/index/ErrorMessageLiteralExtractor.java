package io.graphrag.builder.index;

import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.TreeSet;

/**
 * 핸들러 소스의 {@code ResponseStatusException} 생성자 인자에서 예외 메시지 문자열 리터럴을
 * 추출한다 (REQ-D 캡처부 — 2026-09-01 assertion-provenance 명세).
 *
 * <p>추출 범위: 핸들러 메서드 본문 + 동일 클래스 1단계 호출 메서드. 순수 리터럴 인자는 전체
 * 문자열을, 연결식(concat) 인자는 길이 {@value #MIN_FRAGMENT_LENGTH}자 이상의 리터럴 조각을
 * 기록한다. 결과는 정렬·중복 제거되어 결정적이다(같은 소스 → 같은 graph.json).
 *
 * <p>미지원 형태(조용히 미추출 — 보수적 방향이라 승격이 안 될 뿐 오탐은 없음):
 * {@code String.format(...)}/{@code "...".formatted(...)}/{@code MessageFormat.format(...)}/
 * {@code String.join(...)}, static final 상수 참조, 2단계 이상 호출 체인.
 *
 * <p>생성부(test-generator {@code AssertionProvenanceUpgrader})는 이 목록과 관측 message의
 * 정확 일치 → equalTo, 조각 포함 → containsString으로 승격한다. message가 응답에 노출되지
 * 않는 SUT(Spring 기본 include-message=never)에서는 어설션 대상 필드가 없어 자연히 무효과다.
 */
public final class ErrorMessageLiteralExtractor {

    private static final String TARGET_EXCEPTION = "ResponseStatusException";

    /** 연결식 조각 최소 길이 — 생성부의 containment 최소 길이와 동일해야 한다. */
    private static final int MIN_FRAGMENT_LENGTH = 8;

    private ErrorMessageLiteralExtractor() {
    }

    public static List<String> extract(CtMethod<?> handler) {
        TreeSet<String> out = new TreeSet<>();
        collectFromMethod(handler, out);
        // 동일 클래스 1단계 호출 메서드(가드를 헬퍼로 뽑아낸 관용) — 재귀 없음(v1).
        CtType<?> owner = handler.getDeclaringType();
        if (handler.getBody() != null && owner != null) {
            java.util.Set<String> visited = new java.util.HashSet<>();
            for (CtInvocation<?> inv : handler.getBody().getElements(new TypeFilter<>(CtInvocation.class))) {
                collectFromMethod(sameClassCallee(inv, owner, visited), out);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 호출식이 owner 클래스의 미방문 메서드를 가리키면 그 선언을 반환한다(아니면 null).
     * 선언부 해석(getExecutableDeclaration) 전에 참조 수준의 declaring-type FQN으로 선-필터한다:
     * noClasspath 모델에서 라이브러리 호출마다 클래스로드 실패 경로를 타는 비용을 피하고,
     * 동일 클래스 판정도 Spoon 심층 equals가 아니라 FQN 비교로 한다.
     */
    private static CtMethod<?> sameClassCallee(CtInvocation<?> inv, CtType<?> owner,
                                               java.util.Set<String> visited) {
        boolean sameClass = inv.getExecutable() != null
                && inv.getExecutable().getDeclaringType() != null
                && owner.getQualifiedName().equals(inv.getExecutable().getDeclaringType().getQualifiedName());
        if (!sameClass || !visited.add(inv.getExecutable().getSignature())) {
            return null;
        }
        CtExecutable<?> callee = inv.getExecutable().getExecutableDeclaration();
        return callee instanceof CtMethod<?> m ? m : null;
    }

    private static void collectFromMethod(CtMethod<?> method, TreeSet<String> out) {
        if (method == null || method.getBody() == null) {
            return;
        }
        for (CtConstructorCall<?> call : method.getBody()
                .getElements(new TypeFilter<>(CtConstructorCall.class))) {
            if (call.getType() == null || !TARGET_EXCEPTION.equals(call.getType().getSimpleName())) {
                continue;
            }
            for (CtExpression<?> arg : call.getArguments()) {
                collectLiterals(arg, out, arg instanceof CtBinaryOperator);
            }
        }
    }

    /**
     * 인자 표현식에서 문자열 리터럴을 수집한다. 순수 리터럴은 전체를, 연결식 내부 조각은
     * {@link #MIN_FRAGMENT_LENGTH}자 이상만 담는다(짧은 조각의 우연 포함 오탐 방지).
     */
    private static void collectLiterals(CtExpression<?> expr, TreeSet<String> out, boolean fragment) {
        if (expr instanceof CtLiteral<?> lit && lit.getValue() instanceof String s) {
            if (!fragment || s.length() >= MIN_FRAGMENT_LENGTH) {
                out.add(s);
            }
        } else if (expr instanceof CtBinaryOperator<?> bin) {
            collectLiterals(bin.getLeftHandOperand(), out, true);
            collectLiterals(bin.getRightHandOperand(), out, true);
        }
    }
}
