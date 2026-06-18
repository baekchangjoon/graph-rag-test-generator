package io.graphrag.builder.index;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 모델에서 타입을 찾아 필드 구조(BodyShape)를 추출한다. HTTP/WS 인덱서 공용. */
public final class BodyShapeExtractor {

    private BodyShapeExtractor() {
    }

    public static Optional<BodyShape> extract(CtModel model, String qualifiedName) {
        for (CtType<?> type : model.getAllTypes()) {
            CtType<?> target = findNested(type, qualifiedName);
            if (target == null) {
                continue;
            }
            List<BodyShape.BodyField> fields = new ArrayList<>();
            if (target instanceof CtRecord record) {
                for (CtRecordComponent component : record.getRecordComponents()) {
                    fields.add(new BodyShape.BodyField(
                            component.getSimpleName(),
                            component.getType().getQualifiedName()));
                }
            } else {
                target.getFields().forEach(field -> fields.add(new BodyShape.BodyField(
                        field.getSimpleName(), field.getType().getQualifiedName())));
            }
            return Optional.of(new BodyShape(qualifiedName, fields));
        }
        return Optional.empty();
    }

    private static final java.util.Set<String> COLLECTION_TYPES = java.util.Set.of(
            "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable");
    private static final java.util.Set<String> SCALAR_TYPES = java.util.Set.of(
            "java.lang.String", "java.lang.Boolean", "boolean",
            "java.lang.Integer", "int", "java.lang.Long", "long", "java.lang.Short", "short",
            "java.lang.Double", "double", "java.lang.Float", "float", "java.math.BigDecimal",
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
            "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime");

    public static Optional<BodyShape> extractFromType(CtModel model,
            spoon.reflect.reference.CtTypeReference<?> type) {
        var element = elementType(type);
        if (element == null) {
            return extract(model, type.getQualifiedName());   // 비컬렉션 → 객체(기존)
        }
        return elementShape(model, element).map(s -> new BodyShape(s.javaType(), s.fields(), true));
    }

    private static spoon.reflect.reference.CtTypeReference<?> elementType(
            spoon.reflect.reference.CtTypeReference<?> type) {
        if (type instanceof spoon.reflect.reference.CtArrayTypeReference<?> arr) {
            return arr.getComponentType();
        }
        if (COLLECTION_TYPES.contains(type.getQualifiedName())
                && type.getActualTypeArguments().size() == 1) {
            return type.getActualTypeArguments().get(0);
        }
        return null;
    }

    private static Optional<BodyShape> elementShape(CtModel model,
            spoon.reflect.reference.CtTypeReference<?> element) {
        String qn = element.getQualifiedName();
        CtType<?> decl = element.getTypeDeclaration();
        if (decl != null && decl.isShadow()) {
            decl = null;   // JDK/외부 shadow 타입은 모델 구성원이 아님 → scalar 판정으로 위임
        }
        if (decl == null) {
            decl = findInModel(model, qn);   // noClasspath fallback (다른 파일 원소)
        }
        if (decl != null && decl.isEnum()) {
            return Optional.of(new BodyShape(qn, java.util.List.of()));
        }
        if (decl != null) {
            return extract(model, qn);
        }
        if (SCALAR_TYPES.contains(qn)) {
            return Optional.of(new BodyShape(qn, java.util.List.of()));
        }
        return Optional.empty();
    }

    private static CtType<?> findInModel(CtModel model, String qn) {
        for (CtType<?> t : model.getAllTypes()) {
            CtType<?> found = findNested(t, qn);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public static String bodyTypeKey(spoon.reflect.reference.CtTypeReference<?> type) {
        if (type instanceof spoon.reflect.reference.CtArrayTypeReference<?> arr) {
            return arr.getComponentType().getQualifiedName() + "[]";
        }
        var el = elementType(type);
        return el == null ? type.getQualifiedName()
                : type.getQualifiedName() + "<" + el.getQualifiedName() + ">";
    }

    static CtType<?> findNested(CtType<?> type, String qualifiedName) {
        if (type.getQualifiedName().equals(qualifiedName)) {
            return type;
        }
        for (CtType<?> nested : type.getNestedTypes()) {
            CtType<?> found = findNested(nested, qualifiedName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
