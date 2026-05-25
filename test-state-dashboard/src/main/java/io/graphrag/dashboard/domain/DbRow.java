package io.graphrag.dashboard.domain;

import java.time.Instant;

/** TestRun이 보유한 DB 행 추적 정보. */
public record DbRow(String table, String keyColumn, String keyValue, Instant insertedAt) {}
