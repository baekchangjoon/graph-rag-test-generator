package io.graphrag.builder.index;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * handler 클래스의 enum-스타일 문자열 리터럴을 변이 후보로 수집한다 (Phase 2).
 * 정적 분석이 "EXPRESS" 같은 도메인 상수 분기에 탐색을 안내하는 역할 (docs/22 4번
 * 한계의 부분 보완).
 */
public class LiteralCandidateExtractor {

    private static final Pattern ENUM_STYLE = Pattern.compile("[A-Z][A-Z0-9_]{1,15}");

    public List<String> extract(CtModel model, String classFqn) {
        TreeSet<String> literals = new TreeSet<>();
        for (CtType<?> type : model.getAllTypes()) {
            if (!type.getQualifiedName().replace('$', '.').equals(classFqn)) {
                continue;
            }
            type.getElements(new TypeFilter<>(CtLiteral.class)).forEach(literal -> {
                if (literal.getValue() instanceof String s && ENUM_STYLE.matcher(s).matches()) {
                    literals.add(s);
                }
            });
        }
        return List.copyOf(literals);
    }

    /** SourceRoots 위임 — 전 루트로 모델 1회 빌드 후 {@link #extract(CtModel, String)} 에 위임. */
    public List<String> extract(SourceRoots roots, String classFqn) {
        return extract(SharedSpoonModel.build(roots), classFqn);
    }

    /** Path 위임 — 단일 루트로 {@link #extract(SourceRoots, String)} 에 위임. */
    public List<String> extract(Path srcDir, String classFqn) {
        return extract(SourceRoots.single(srcDir), classFqn);
    }
}
