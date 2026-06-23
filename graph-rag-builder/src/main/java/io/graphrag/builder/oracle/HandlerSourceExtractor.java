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

    /** 메서드 본문 소스. 클래스/메서드 미존재 시 빈 문자열. */
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
                .map(Object::toString)
                .findFirst().orElse("");
    }
}
