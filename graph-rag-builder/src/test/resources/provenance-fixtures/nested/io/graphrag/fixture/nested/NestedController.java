package io.graphrag.fixture.nested;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — DTO 중첩 재귀 전개(REQ-034). 실제 SUT
 * ({@code TransferController.CreateTransferRequest}/{@code TransferItem}) 관례를 미러링한다:
 * record 기반 요청 DTO의 {@code List} 원소 필드 접근({@code req.items().get(0).qty()})은
 * 대표원소(첫 원소) 규약으로 bracket 없이 dot-path {@code "items.qty"}로 평탄화되고, {@code Map}
 * 키 접근({@code req.configs().get("region")})은 {@code "configs.region"}으로 평탄화되어 각각
 * INPUT으로 태깅되어야 한다.
 */
@RestController
@RequestMapping("/api/nested")
public class NestedController {

    public record Item(int qty) {
    }

    public record CreateRequest(List<Item> items, Map<String, String> configs) {
    }

    @PostMapping
    public String create(@RequestBody CreateRequest req) {
        if (req.items() == null || req.items().isEmpty() || req.items().get(0).qty() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid items");
        }
        return "OK";
    }

    @PostMapping("/by-config")
    public String createByConfig(@RequestBody CreateRequest req) {
        if (req.configs() == null || req.configs().get("region") == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "missing region");
        }
        return "OK";
    }

    /**
     * 대표원소(인덱스 0) 규약의 경계 케이스: {@code get(1)}은 downstream
     * {@code InputMutator.applyToBody}의 대표원소 변이 대상(항상 {@code arr.get(0)})과 어긋나므로
     * "items.qty"로 태깅되면 안 되고 UNKNOWN으로 남아야 한다.
     */
    @PostMapping("/second-item")
    public String createSecondItem(@RequestBody CreateRequest req) {
        if (req.items().get(1).qty() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid second item");
        }
        return "OK";
    }
}
