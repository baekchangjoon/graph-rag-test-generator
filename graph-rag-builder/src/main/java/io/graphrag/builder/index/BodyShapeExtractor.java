package io.graphrag.builder.index;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/** 모델에서 타입을 찾아 필드 구조(BodyShape)를 추출한다. HTTP/WS 인덱서 공용. */
public final class BodyShapeExtractor {

    private BodyShapeExtractor() {
    }

    /**
     * 중첩 depth 상한 (컴포넌트 depth=0이 첫 번째 레벨).
     * depth >= MAX_NESTING_DEPTH 이면 expand 없이 타입 자체를 리프로 emit.
     * 결과 dot-path는 최대 MAX_NESTING_DEPTH개 세그먼트(예: "b.c.d").
     */
    private static final int MAX_NESTING_DEPTH = 2;

    public static Optional<BodyShape> extract(CtModel model, String qualifiedName) {
        for (CtType<?> type : model.getAllTypes()) {
            CtType<?> target = findNested(type, qualifiedName);
            if (target == null) {
                continue;
            }
            List<BodyShape.BodyField> fields = new ArrayList<>();
            // 루트 타입을 visited에 넣어 self-cycle 차단
            Set<String> visited = new HashSet<>();
            visited.add(qualifiedName);
            collectComponents(target, (compName, compType) ->
                    flatten(model, "", compName, compType, 0, visited, fields));
            return Optional.of(new BodyShape(qualifiedName, fields));
        }
        return Optional.empty();
    }

    /**
     * compName/compType을 dot-path 스칼라 리프들로 전개한다.
     * visited는 per-path(스택-로컬) cycle guard — 형제 필드가 같은 타입을 참조해도 각자 전개된다.
     */
    private static void flatten(CtModel model, String prefix, String compName,
            CtTypeReference<?> type, int depth, Set<String> visited,
            List<BodyShape.BodyField> out) {
        String path = prefix.isEmpty() ? compName : prefix + "." + compName;
        String qn = type.getQualifiedName();

        // collection 요소 타입이 있으면 컬렉션 리프로 emit
        boolean isCollection = elementType(type) != null;
        boolean isScalar = SCALAR_TYPES.contains(qn);
        boolean depthExceeded = depth >= MAX_NESTING_DEPTH;

        if (isScalar || isCollection || depthExceeded) {
            out.add(new BodyShape.BodyField(path, qn));
            return;
        }

        CtType<?> decl = type.getTypeDeclaration();
        if (decl != null && decl.isShadow()) {
            decl = null;
        }
        if (decl == null) {
            decl = findInModel(model, qn);
        }

        // enum, 미해결 타입, 이미 방문한 타입(사이클) → 리프 emit
        boolean isEnum = decl != null && decl.isEnum();
        boolean unresolved = decl == null;
        boolean cycle = visited.contains(qn);

        if (isEnum || unresolved || cycle) {
            out.add(new BodyShape.BodyField(path, qn));
            return;
        }

        // 중첩 DTO expand — per-path visited 복사본 사용(형제 간 독립)
        Set<String> next = new HashSet<>(visited);
        next.add(qn);
        List<BodyShape.BodyField> children = new ArrayList<>();
        collectComponents(decl, (cn, ct) -> flatten(model, path, cn, ct, depth + 1, next, children));

        // 빈 nested DTO(컴포넌트 없음 등) → 경로 자체를 리프로 emit
        if (children.isEmpty()) {
            out.add(new BodyShape.BodyField(path, qn));
        } else {
            out.addAll(children);
        }
    }

    /** record면 record components, class면 instance fields를 (name, typeRef) 콜백으로 순서대로 제공. */
    private static void collectComponents(CtType<?> type,
            BiConsumer<String, CtTypeReference<?>> consumer) {
        if (type instanceof CtRecord record) {
            for (CtRecordComponent comp : record.getRecordComponents()) {
                consumer.accept(comp.getSimpleName(), comp.getType());
            }
        } else {
            for (var field : type.getFields()) {
                consumer.accept(field.getSimpleName(), field.getType());
            }
        }
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
