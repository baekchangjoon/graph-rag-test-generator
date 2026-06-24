package io.graphrag.builder.oracle;

import io.graphrag.builder.explore.ConditionBoundarySolver;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.ConstraintExtractor.StringEquality;
import io.graphrag.builder.index.SharedSpoonModel;
import spoon.reflect.CtModel;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 소스에 리터럴로 박힌 비교식·문자열 동치를 Spoon AST로 추출하는 InputOracle 구현.
 * concolic이 못/안 다루는 단순 케이스를 싸게 커버 (둘은 merge되어 합쳐짐).
 */
public final class StaticLiteralOracle implements InputOracle {

    /** 탐색 단계가 주입한 공유 Spoon 모델(null이면 analyze에서 sut.roots()로 1회 빌드). */
    private final CtModel sharedModel;

    public StaticLiteralOracle() {
        this.sharedModel = null;
    }

    /** 공유 모델 주입 — 탐색 단계 단일 모델 재사용(추가 빌드 없음). */
    public StaticLiteralOracle(CtModel sharedModel) {
        this.sharedModel = sharedModel;
    }

    @Override
    public String name() {
        return "static-literal";
    }

    @Override
    public InputCandidates analyze(SutCode sut) {
        CtModel model = sharedModel != null ? sharedModel : SharedSpoonModel.build(sut.roots());
        ConstraintExtractor extractor = new ConstraintExtractor();
        Map<String, Set<Long>> numeric =
                new ConditionBoundarySolver().solve(extractor.extractComparisons(model));
        Map<String, Set<String>> strings = new TreeMap<>();
        for (StringEquality se : extractor.extractStringEqualities(model)) {
            strings.computeIfAbsent(se.fieldRef(), k -> new TreeSet<>()).add(se.value());
        }
        return new InputCandidates(numeric, strings);
    }
}
