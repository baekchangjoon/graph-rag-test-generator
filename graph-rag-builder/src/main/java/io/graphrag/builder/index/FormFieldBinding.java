package io.graphrag.builder.index;

/**
 * @Controller 폼 커맨드의 한 필드가 어떤 Spring 데이터 바인딩을 받는지 나타내는 정적 메타데이터.
 * 인덱서가 산출(static 분석만 — DB 미기동)하고 러너/합성기가 소비한다. 런타임에서만 알 수 있는 PK·name
 * 컬럼은 여기 두지 않고 러너가 {@code TableSchema}로 해석한다. 백업 테이블명은 정적 신호(@JoinColumn FK,
 * @Table)만 캐리하고 미상이면 러너가 camelToSnake로 폴백한다.
 *
 * @param field         커맨드 필드명(폼 파라미터 키)
 * @param javaType      필드 선언 타입 FQN
 * @param kind          바인딩 종류(SCALAR/REFERENCE/NESTED)
 * @param refEntityFqn  REFERENCE일 때 참조 엔티티 타입 FQN(백업 테이블 해석 입력), 아니면 null
 * @param joinColumn    REFERENCE이고 {@code @ManyToOne @JoinColumn(name=...)}가 있으면 그 FK 컬럼명, 아니면 null
 * @param refTable      REFERENCE이고 참조 타입에 {@code @Table(name=...)}가 있으면 그 테이블명(정적), 아니면 null
 * @param nestedTypeFqn NESTED일 때 중첩 POJO 타입 FQN(점-경로 재귀 평면화 입력), 아니면 null
 */
public record FormFieldBinding(
        String field,
        String javaType,
        Kind kind,
        String refEntityFqn,
        String joinColumn,
        String refTable,
        String nestedTypeFqn) {

    public enum Kind {
        SCALAR,
        REFERENCE,
        NESTED
    }

    public static FormFieldBinding scalar(String field, String javaType) {
        return new FormFieldBinding(field, javaType, Kind.SCALAR, null, null, null, null);
    }

    public static FormFieldBinding reference(String field, String javaType, String refEntityFqn,
                                             String joinColumn, String refTable) {
        return new FormFieldBinding(field, javaType, Kind.REFERENCE, refEntityFqn, joinColumn, refTable, null);
    }

    public static FormFieldBinding nested(String field, String javaType, String nestedTypeFqn) {
        return new FormFieldBinding(field, javaType, Kind.NESTED, null, null, null, nestedTypeFqn);
    }
}
