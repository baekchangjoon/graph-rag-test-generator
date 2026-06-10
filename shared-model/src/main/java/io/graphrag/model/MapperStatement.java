package io.graphrag.model;

/** MyBatis XML mapper statement 사실 (L1, Phase 1). */
public record MapperStatement(
        String id,
        String namespace,
        String statementId,
        String sqlKind,
        boolean dynamic,
        String sourceXml) {
}
