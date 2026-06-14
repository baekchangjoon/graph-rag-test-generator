package io.graphrag.builder.oracle;

import io.graphrag.builder.explore.ConditionBoundarySolver;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.ConstraintExtractor.StringEquality;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 소스에 리터럴로 박힌 비교식·문자열 동치를 Spoon AST로 추출하는 InputOracle 구현.
 * concolic이 못/안 다루는 단순 케이스를 싸게 커버 (둘은 merge되어 합쳐짐).
 */
public final class StaticLiteralOracle implements InputOracle {

    @Override
    public String name() {
        return "static-literal";
    }

    @Override
    public InputCandidates analyze(SutCode sut) {
        ConstraintExtractor extractor = new ConstraintExtractor();
        Map<String, Set<Long>> numeric =
                new ConditionBoundarySolver().solve(extractor.extractComparisons(sut.srcDir()));
        Map<String, Set<String>> strings = new TreeMap<>();
        for (StringEquality se : extractor.extractStringEqualities(sut.srcDir())) {
            strings.computeIfAbsent(se.fieldRef(), k -> new TreeSet<>()).add(se.value());
        }
        return new InputCandidates(numeric, strings);
    }
}
