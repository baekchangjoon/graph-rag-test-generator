package io.graphrag.builder.oracle;

import io.graphrag.builder.index.SharedSpoonModel;
import io.graphrag.builder.index.SourceRoots;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.util.Objects;

/** 핸들러 (class, method) → 메서드 본문 소스 텍스트(Spoon). 캐시 키 해시·LLM 프롬프트 입력용. */
public final class HandlerSourceExtractor {
    private final SourceRoots roots;
    private CtModel model;   // lazy 단일 파싱

    public HandlerSourceExtractor(SourceRoots roots) {
        this.roots = roots;
    }

    /** 공유 모델 주입 — 빌드 없이 주어진 CtModel을 그대로 사용(탐색 단계 단일 모델 재사용). */
    public HandlerSourceExtractor(CtModel model) {
        this.roots = null;
        this.model = model;
    }

    /** Path 위임 — 단일 루트로 {@link #HandlerSourceExtractor(SourceRoots)} 에 위임. */
    public HandlerSourceExtractor(Path srcDir) {
        this(SourceRoots.single(srcDir));
    }

    private CtModel model() {
        if (model == null) {
            model = SharedSpoonModel.build(roots);
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
