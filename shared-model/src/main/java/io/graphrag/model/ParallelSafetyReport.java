package io.graphrag.model;

import java.util.List;

/** docs/06 parallel_safety_report. 도구 2가 오케스트레이터에 알리는 병렬 안전성. */
public record ParallelSafetyReport(
        List<String> fullyParallel,
        List<SerialRequired> serialRequired) {
}
