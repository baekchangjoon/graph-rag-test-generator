package io.graphrag.fixture.enumleaf;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ProvenanceIndexerIT 픽스처 — enum 타입 필드가 리프로 남아야 한다. */
@RestController
@RequestMapping("/api/enum-leaf")
public class EnumLeafController {

    public record BookingRequest(String guestName, PriceTier priceTier, int nights) {
    }

    @PostMapping
    public String create(@RequestBody BookingRequest req) {
        if (req.nights() < 1) {
            throw new IllegalArgumentException("nights must be positive");
        }
        return "OK";
    }

    /**
     * 바디 전체에 대한 null 가드 — petclinic ReservationRestController.create와 동일 형태.
     * {@code req}는 필드가 아니라 바디 루트이므로 채울 자리가 없다. 이걸 필드로 착각하면
     * body에 존재하지 않는 유령 키가 생긴다.
     */
    @PostMapping("/null-check")
    public String createWithBodyNullCheck(@RequestBody BookingRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return "OK:" + req.guestName();
    }
}
