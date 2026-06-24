package io.graphrag.builder.index;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code @RequestBody} DTO의 jakarta.validation 제약을 정적으로 읽어 위반/경계 입력
 * 생성의 근거로 삼는다. 콘콜릭/SMT 없이 선언적 제약을 그대로 환류
 * (docs/decisions/explorer-engines.md). 제약값이 애너테이션에 리터럴로 있으므로 솔버 불필요.
 */
public class ValidationConstraintExtractor {

    public enum Kind {
        NOT_NULL, NOT_BLANK, SIZE_MIN, SIZE_MAX, MIN, MAX,
        POSITIVE, POSITIVE_OR_ZERO, NEGATIVE, NEGATIVE_OR_ZERO, EMAIL, PATTERN
    }

    /** numArg: MIN/MAX/SIZE_* 한정. strArg: PATTERN 한정(regexp). 그 외 0/null. */
    public record FieldConstraint(String field, Kind kind, long numArg, String strArg) {
    }

    public Map<String, List<FieldConstraint>> extract(SourceRoots roots, String dtoQualifiedName) {
        CtModel model = SharedSpoonModel.build(roots);

        Map<String, List<FieldConstraint>> result = new LinkedHashMap<>();
        for (CtType<?> type : model.getAllTypes()) {
            CtType<?> dto = BodyShapeExtractor.findNested(type, dtoQualifiedName);
            if (dto == null) {
                continue;
            }
            if (dto instanceof CtRecord record) {
                for (CtRecordComponent comp : record.getRecordComponents()) {
                    collect(result, comp.getSimpleName(),
                            mergedAnnotations(comp, record.getField(comp.getSimpleName())));
                }
            } else {
                dto.getFields().forEach(f ->
                        collect(result, f.getSimpleName(), f.getAnnotations()));
            }
            return result;
        }
        return result;
    }

    /** Path 위임 — 단일 루트로 {@link #extract(SourceRoots, String)} 에 위임. */
    public Map<String, List<FieldConstraint>> extract(Path srcDir, String dtoQualifiedName) {
        return extract(SourceRoots.single(srcDir), dtoQualifiedName);
    }

    /**
     * 검증 애너테이션이 record component 노드에 붙는지 backing field 노드에 붙는지는
     * Spoon 버전·@Target에 따라 갈린다. 양쪽을 합쳐(타입 simpleName 기준 dedupe) 어느
     * 쪽이든 읽히게 한다. backing field가 없으면(component만) component만 사용.
     */
    private static List<CtAnnotation<?>> mergedAnnotations(CtRecordComponent comp,
                                                           CtField<?> backingField) {
        LinkedHashMap<String, CtAnnotation<?>> byType = new LinkedHashMap<>();
        for (CtAnnotation<?> a : comp.getAnnotations()) {
            byType.putIfAbsent(a.getAnnotationType().getSimpleName(), a);
        }
        if (backingField != null) {
            for (CtAnnotation<?> a : backingField.getAnnotations()) {
                byType.putIfAbsent(a.getAnnotationType().getSimpleName(), a);
            }
        }
        return new ArrayList<>(byType.values());
    }

    private static void collect(Map<String, List<FieldConstraint>> result, String field,
                                List<CtAnnotation<?>> annotations) {
        List<FieldConstraint> constraints = new ArrayList<>();
        for (CtAnnotation<?> ann : annotations) {
            switch (ann.getAnnotationType().getSimpleName()) {
                case "NotNull" -> constraints.add(new FieldConstraint(field, Kind.NOT_NULL, 0, null));
                case "NotBlank", "NotEmpty" ->
                        constraints.add(new FieldConstraint(field, Kind.NOT_BLANK, 0, null));
                case "Min" -> longAttr(ann, "value").ifPresent(v ->
                        constraints.add(new FieldConstraint(field, Kind.MIN, v, null)));
                case "Max" -> longAttr(ann, "value").ifPresent(v ->
                        constraints.add(new FieldConstraint(field, Kind.MAX, v, null)));
                case "Size" -> {
                    longAttr(ann, "min").filter(m -> m > 0).ifPresent(m ->
                            constraints.add(new FieldConstraint(field, Kind.SIZE_MIN, m, null)));
                    longAttr(ann, "max").filter(m -> m < Integer.MAX_VALUE).ifPresent(m ->
                            constraints.add(new FieldConstraint(field, Kind.SIZE_MAX, m, null)));
                }
                case "Positive" -> constraints.add(new FieldConstraint(field, Kind.POSITIVE, 0, null));
                case "PositiveOrZero" ->
                        constraints.add(new FieldConstraint(field, Kind.POSITIVE_OR_ZERO, 0, null));
                case "Negative" -> constraints.add(new FieldConstraint(field, Kind.NEGATIVE, 0, null));
                case "NegativeOrZero" ->
                        constraints.add(new FieldConstraint(field, Kind.NEGATIVE_OR_ZERO, 0, null));
                case "Email" -> constraints.add(new FieldConstraint(field, Kind.EMAIL, 0, null));
                case "Pattern" -> strAttr(ann, "regexp").ifPresent(rx ->
                        constraints.add(new FieldConstraint(field, Kind.PATTERN, 0, rx)));
                default -> { }
            }
        }
        if (!constraints.isEmpty()) {
            result.put(field, constraints);
        }
    }

    // 검증된 패턴: 이 코드베이스는 annotation.getValues().get(key)로 속성을 읽는다
    // (EndpointIndexer/WsEndpointIndexer). getValue(key)가 아니라 getValues().get(key).
    private static Optional<Long> longAttr(CtAnnotation<?> ann, String key) {
        CtExpression<?> v = ann.getValues().get(key);
        if (v instanceof CtLiteral<?> lit && lit.getValue() instanceof Number n) {
            return Optional.of(n.longValue());
        }
        return Optional.empty();
    }

    private static Optional<String> strAttr(CtAnnotation<?> ann, String key) {
        CtExpression<?> v = ann.getValues().get(key);
        if (v instanceof CtLiteral<?> lit && lit.getValue() instanceof String s) {
            return Optional.of(s);
        }
        return Optional.empty();
    }
}
