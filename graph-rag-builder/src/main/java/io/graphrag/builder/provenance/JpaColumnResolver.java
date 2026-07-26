package io.graphrag.builder.provenance;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

/**
 * JPA 엔티티의 테이블/컬럼명을 해석한다: {@code @Table(name=)}/{@code @Column(name=)} 오버라이드를
 * 우선하고, 없으면 camelToSnake 폴백을 쓴다(REQ-004).
 *
 * <p>어노테이션 속성은 이 코드베이스의 검증된 관례({@code EndpointIndexer}/{@code
 * ValidationConstraintExtractor})를 따라 {@code CtAnnotation.getValues().get(key)}로 읽는다
 * ({@code getValue(key)}가 아니다). 어노테이션 매칭은 simple-name 기준(noClasspath 모드에서
 * wildcard import 시 FQN이 오해석될 수 있어 보수적으로 simple-name을 비교).
 */
public class JpaColumnResolver {

    private final CtModel model;

    public JpaColumnResolver(CtModel model) {
        this.model = model;
    }

    /** 해석 결과: 테이블명 + 컬럼명. */
    public record TableColumn(String table, String column) {
    }

    /**
     * entityType의 getterName(예: "getBalance")이 가리키는 필드의 테이블/컬럼명을 해석한다.
     * entityType이 모델에서 찾아지지 않으면(라이브러리 타입 등) camelToSnake 폴백만 적용한다.
     */
    public TableColumn resolve(CtTypeReference<?> entityType, String getterName) {
        CtType<?> type = entityType == null ? null : resolveType(entityType.getQualifiedName());
        String fieldName = fieldNameFromGetter(getterName);
        return new TableColumn(tableName(type, entityType), columnName(type, fieldName));
    }

    private String tableName(CtType<?> type, CtTypeReference<?> entityType) {
        if (type != null) {
            String override = annotationStringValue(type, "Table", "name");
            if (override != null) {
                return override;
            }
        }
        String simpleName = type != null ? type.getSimpleName()
                : (entityType != null ? entityType.getSimpleName() : "");
        return camelToSnake(simpleName);
    }

    private String columnName(CtType<?> type, String fieldName) {
        if (type != null) {
            CtField<?> field = type.getField(fieldName);
            if (field != null) {
                String override = annotationStringValue(field, "Column", "name");
                if (override != null) {
                    return override;
                }
            }
        }
        return camelToSnake(fieldName);
    }

    private static String annotationStringValue(spoon.reflect.declaration.CtElement element,
                                                  String annotationSimpleName, String attributeKey) {
        for (CtAnnotation<?> annotation : element.getAnnotations()) {
            if (!annotationSimpleName.equals(annotation.getAnnotationType().getSimpleName())) {
                continue;
            }
            CtExpression<?> value = annotation.getValues().get(attributeKey);
            if (value instanceof CtLiteral<?> literal && literal.getValue() instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /** {@code getFoo}/{@code isFoo} → {@code foo}. 어느 접두사도 아니면 그대로. */
    private static String fieldNameFromGetter(String getterName) {
        if (getterName.startsWith("get") && getterName.length() > 3) {
            return decapitalize(getterName.substring(3));
        }
        if (getterName.startsWith("is") && getterName.length() > 2
                && Character.isUpperCase(getterName.charAt(2))) {
            return decapitalize(getterName.substring(2));
        }
        return getterName;
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** 타입 FQN으로 모델에서 {@code CtType}을 찾는다(중첩 타입 포함, {@code ProvenanceIndexer.resolveType}과 동일 관례). */
    private CtType<?> resolveType(String typeFqn) {
        if (typeFqn == null || typeFqn.isEmpty()) {
            return null;
        }
        String normalized = typeFqn.replace('$', '.');
        for (CtType<?> type : model.getAllTypes()) {
            CtType<?> found = findNestedType(type, normalized);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static CtType<?> findNestedType(CtType<?> type, String qualifiedName) {
        if (type.getQualifiedName().replace('$', '.').equals(qualifiedName)) {
            return type;
        }
        for (CtType<?> nested : type.getNestedTypes()) {
            CtType<?> found = findNestedType(nested, qualifiedName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** {@code ReadInputSynthesizer.camelToSnake}과 동일 로직(snake 변환 1함수 중복 구현 허용 범위). */
    static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
