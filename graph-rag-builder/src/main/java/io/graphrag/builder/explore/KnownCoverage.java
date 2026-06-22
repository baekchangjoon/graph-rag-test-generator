package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.BranchRef;
import io.graphrag.model.Outcome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 엔진 간에 공유·누적되는 커버리지 상태 (docs/05의 coverage merge). */
public final class KnownCoverage {

    /** 변이 시드 = 새 분기를 연 입력 + 그때의 응답 status + 분류된 outcome kind.
     *  kind는 시드 큐 정렬(SUCCESS 우선)에 쓰인다 — 시드는 응답 body를 저장하지 않아 사후 재분류 불가. */
    public record Seed(JsonNode body, int status, Outcome.Kind kind) {
    }

    private final Set<BranchRef> covered = new LinkedHashSet<>();
    private final List<Seed> seeds = new ArrayList<>();
    private final Set<String> triedBodies = new HashSet<>();

    public Set<BranchRef> covered() {
        return covered;
    }

    public boolean isNovel(Set<BranchRef> branches) {
        return !covered.containsAll(branches);
    }

    public void merge(Set<BranchRef> branches) {
        covered.addAll(branches);
    }

    /** 새 분기를 연 입력은 후속 엔진의 변이 시드가 된다. */
    public void addSeed(JsonNode body, int status, Outcome.Kind kind) {
        seeds.add(new Seed(body, status, kind));
    }

    public List<Seed> seeds() {
        return List.copyOf(seeds);
    }

    /** 동일 body 재시도 방지 (예산 절약 + 결정성). 처음 보는 body면 true. */
    public boolean markTried(JsonNode body) {
        return triedBodies.add(body.toString());
    }
}
