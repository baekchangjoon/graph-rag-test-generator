package io.graphrag.builder.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REQ-005/006/007: graph 모델을 투영해 coverage-by-path.json을 쓴다. */
public final class CoverageByPathReport {

    private static final Logger log = LoggerFactory.getLogger(CoverageByPathReport.class);
    private static final String EXEC_REL = "work/pjacoco-exec";

    private CoverageByPathReport() {}

    public static void write(GraphAsset asset, Path outDir) {
        Path execDir = outDir.resolve(EXEC_REL);
        List<Map<String, Object>> paths = new ArrayList<>();
        for (ExploredPath p : asset.paths()) {
            List<Map<String, Object>> execFiles = new ArrayList<>();
            for (String tid : p.coverageTraceIds()) {
                Map<String, Object> ef = new LinkedHashMap<>();
                ef.put("traceId", tid);
                ef.put("exec", EXEC_REL + "/" + tid + ".exec");
                ef.put("sidecar", EXEC_REL + "/" + tid + ".json");
                ef.put("summary", readSummary(execDir.resolve(tid + ".json")));
                execFiles.add(ef);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pathId", p.id());
            entry.put("endpointId", p.endpointId());
            entry.put("coverageTraceIds", p.coverageTraceIds());
            entry.put("execFiles", execFiles);
            paths.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sutId", asset.sutId());
        root.put("execDir", EXEC_REL);
        root.put("paths", paths);
        try {
            Files.writeString(outDir.resolve("coverage-by-path.json"),
                    Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            log.warn("coverage-by-path.json 작성 실패(무시): {}", e.toString());
        }
    }

    /** 사이드카에서 summary 투영. 부재·손상 시 null(throw 없음). */
    private static Map<String, Object> readSummary(Path sidecar) {
        if (!Files.exists(sidecar)) return null;
        try {
            JsonNode n = Json.mapper().readTree(Files.readString(sidecar));
            Map<String, Object> s = new LinkedHashMap<>();
            if (n.has("classCount")) s.put("classCount", n.get("classCount").asInt());
            if (n.hasNonNull("result")) s.put("result", n.get("result").asText());
            if (n.has("status")) s.put("status", n.get("status").asText());
            if (n.has("durationMs")) s.put("durationMs", n.get("durationMs").asLong());
            return s;
        } catch (Exception e) {
            log.warn("사이드카 파싱 실패 {} (summary=null): {}", sidecar, e.toString());
            return null;
        }
    }
}
