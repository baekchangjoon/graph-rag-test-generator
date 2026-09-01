package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ErrorMessageLiteralExtractor 단위 테스트 (REQ-D — 예외 메시지 리터럴 provenance 캡처부).
 *
 * <p>sample-src ErrMsgController의 패턴:
 * <ol>
 *   <li>순수 리터럴 reason → 전체 문자열 추출</li>
 *   <li>동일 클래스 1단계 헬퍼(requireTier)의 throw → 추출</li>
 *   <li>ResponseStatusException 외 예외 타입 → 미추출</li>
 *   <li>연결식 reason → 8자 이상 리터럴 조각만 추출("bk "는 제외)</li>
 * </ol>
 */
class ErrorMessageLiteralExtractorTest {

    private CtMethod<?> handler(String name) {
        Launcher l = new Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        l.addInputResource("src/test/resources/errmsg-src");
        CtModel model = l.buildModel();
        for (CtType<?> type : model.getElements(new TypeFilter<>(CtType.class))) {
            for (CtMethod<?> m : type.getMethods()) {
                if (m.getSimpleName().equals(name)) {
                    return m;
                }
            }
        }
        throw new IllegalStateException("handler not found: " + name);
    }

    @Test
    void pureLiteral_andSameClassHelper_extracted_otherExceptionIgnored() {
        List<String> literals = ErrorMessageLiteralExtractor.extract(handler("create"));

        // containsExactly가 타 예외("not a response status message") 미포함까지 함께 고정한다.
        assertThat(literals).containsExactly(
                "nights must be between 1 and 30",
                "tier is required");
    }

    @Test
    void concatReason_longFragmentsOnly() {
        List<String> literals = ErrorMessageLiteralExtractor.extract(handler("get"));

        assertThat(literals).containsExactly(" not found");
    }
}
