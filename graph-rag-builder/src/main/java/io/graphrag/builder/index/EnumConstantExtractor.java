package io.graphrag.builder.index;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SUT 소스의 enum FQN → 선언 순서 상수명 목록. happy 입력의 enum 필드를 유효 상수로 합성하는 근거.
 * 키는 raw {@code getQualifiedName()}($ 구분) — BodyShapeExtractor가 BodyField.javaType에 쓰는 것과 동일 포맷.
 */
public class EnumConstantExtractor {

    public Map<String, List<String>> extract(Path srcDir) {
        return extract(SharedSpoonModel.build(srcDir));
    }

    public Map<String, List<String>> extract(CtModel model) {
        Map<String, List<String>> result = new TreeMap<>();
        for (CtEnum<?> e : model.getElements(new TypeFilter<>(CtEnum.class))) {
            result.put(e.getQualifiedName(),
                    e.getEnumValues().stream().map(v -> v.getSimpleName()).toList());
        }
        return result;
    }
}
