package io.graphrag.builder.oracle;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.util.Objects;

/** 핸들러 (class, method) → 메서드 본문 소스 텍스트(Spoon). 캐시 키 해시·LLM 프롬프트 입력용. */
public final class HandlerSourceExtractor {
    private final Path srcDir;
    private CtModel model;   // lazy 단일 파싱

    public HandlerSourceExtractor(Path srcDir) {
        this.srcDir = srcDir;
    }

    private CtModel model() {
        if (model == null) {
            Launcher launcher = new Launcher();
            launcher.getEnvironment().setNoClasspath(true);
            launcher.addInputResource(srcDir.toString());
            launcher.buildModel();
            model = launcher.getModel();
        }
        return model;
    }

    /**
     * 메서드 본문 소스. 클래스/메서드 미존재 시 빈 문자열. Spoon pretty-printer가 일부 구문
     * (no-classpath 모드의 enum {@code switch case} 라벨 등)에서 NPE를 던질 수 있으므로
     * toString 실패는 빈 문자열로 흡수한다 — 한 핸들러의 인쇄 실패가 전체 LLM 오라클(및 빌드)을
     * 중단시키면 안 된다(REQ-005 회귀 가드).
     */
    public String extract(String handlerClass, String handlerMethod) {
        CtType<?> type = model().getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals(handlerClass))
                .findFirst().orElse(null);
        if (type == null) {
            return "";
        }
        return type.getMethodsByName(handlerMethod).stream()
                .map(CtMethod::getBody)
                .filter(Objects::nonNull)
                .map(HandlerSourceExtractor::safeToString)
                .findFirst().orElse("");
    }

    /** Spoon 인쇄 실패(예: no-classpath enum switch case NPE)를 빈 문자열로 흡수. */
    private static String safeToString(Object element) {
        try {
            return element.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
