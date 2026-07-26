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
}
