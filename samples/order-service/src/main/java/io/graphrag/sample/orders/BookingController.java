package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$");

    public record CreateBookingRequest(String customerEmail, Integer nights, Integer loyaltyPoints,
                                       BookingTier tier, LocalDate checkInDate) {
    }

    public record UpdateBookingRequest(Integer nights, BookingStatus status) {
    }

    public record BookingResponse(Long id, String customerEmail, int nights, int loyaltyPoints,
                                  String tier, String status, String checkInDate) {
    }

    private final BookingRepository bookings;

    public BookingController(BookingRepository bookings) {
        this.bookings = bookings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@RequestBody CreateBookingRequest req) {
        if (req.nights() == null || req.nights() < 1 || req.nights() > 30) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "nights must be between 1 and 30");
        }
        if (req.tier() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "tier is required");
        }
        // 다필드 conjunction (Stage 1/2): enum + numeric 동시 조건
        if (req.tier() == BookingTier.VIP && req.loyaltyPoints() != null && req.loyaltyPoints() < 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VIP requires at least 500 loyalty points");
        }
        if (req.customerEmail() == null || !EMAIL.matcher(req.customerEmail()).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "customerEmail is not a valid email");
        }
        if (req.checkInDate() == null || !req.checkInDate().isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "checkInDate must be in the future");
        }
        // inter-field 가드(Stage 4 벤치마크): loyaltyPoints == nights*600+7. 두 필드를 한 식에 엮으므로
        // 필드별 경계/large 변이로는 동시충족 불가 — Z3 inter-field solveTuple만 (607,1)을 도출한다.
        if (req.loyaltyPoints() != null && req.loyaltyPoints() != req.nights() * 600 + 7) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "loyaltyPoints must equal nights*600+7");
        }
        int loyalty = req.loyaltyPoints() == null ? 0 : req.loyaltyPoints();
        Booking saved = bookings.save(new Booking(req.customerEmail(), req.nights(), loyalty,
                req.tier(), BookingStatus.PENDING, req.checkInDate()));
        return toResponse(saved);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable Long id,
                               @RequestParam(defaultValue = "true") boolean includeStale) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id must be a positive integer");
        }
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not found"));
        if (!includeStale && b.getCheckInDate().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " is stale");
        }
        return toResponse(b);
    }

    @PutMapping("/{id}")
    public BookingResponse update(@PathVariable Long id, @RequestBody UpdateBookingRequest req) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id must be a positive integer");
        }
        if (req == null || (req.nights() == null && req.status() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one of nights or status must be provided");
        }
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not found"));
        if (req.nights() != null) {
            if (req.nights() < 1 || req.nights() > 30) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "nights must be between 1 and 30");
            }
            b.setNights(req.nights());
        }
        if (req.status() != null) {
            b.setStatus(req.status());
        }
        return toResponse(bookings.save(b));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestParam(defaultValue = "false") boolean confirm) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id must be a positive integer");
        }
        if (!confirm) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirm=true is required to delete");
        }
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not found"));
        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() != BookingStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only PENDING or CANCELLED bookings can be deleted");
        }
        bookings.delete(b);
        return ResponseEntity.noContent().build();
    }

    /**
     * 복합 AND 상태 가드(StateGuard conjunction, REQ-010): 저장된 행의 status==CONFIRMED && tier==VIP
     * 두 조건이 동시에 참일 때만 200, 그 외 404. 빌더가 이 conjunction 가드를 검출하면
     * CONFIRMED+VIP 시드 행(200 arm)을 discoveredBy="state-guard-conjunction"로 생성해야 한다.
     */
    @GetMapping("/{id}/premium-eligible")
    public BookingResponse premiumEligible(@PathVariable Long id) {
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not found"));
        if (b.getStatus() == BookingStatus.CONFIRMED && b.getTier() == BookingTier.VIP) {
            return toResponse(b);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not premium-eligible");
    }

    /**
     * NUMERIC 파라미터 가드(입력-주도 시드 변종 Phase 1): 저장된 행의 nights 값이 minNights 이상이면 200,
     * 미만이면 404. 빌더가 이 GE 가드를 검출하면 nights=V(200 arm)와 nights=V-1(404 arm) 두 시드 변종을
     * 생성해야 한다. 두 시드는 서로 다른 PK의 격리된 행이어야 한다.
     */
    @GetMapping("/{id}/eligibility")
    public BookingResponse eligibility(@PathVariable Long id, @RequestParam int minNights) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id must be a positive integer");
        }
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not found"));
        if (b.getNights() >= minNights) {
            return toResponse(b);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " below minNights " + minNights);
    }

    /**
     * 상태머신 다중 전이 회귀 가드(작업 #5): 저장된 행의 status enum 값에 따라 세 arm(200/409/410)으로 갈린다.
     * 각 상태가 명시 == 비교라 빌더가 EQ 가드를 추출하고 각 상태 변종 시드로 세 arm을 모두 캡처해야 한다
     * (다중 변종 미적용으로 되돌리면 happy 상태 1 arm만 → 회귀 시 FAIL).
     */
    @PostMapping("/{id}/advance")
    public ResponseEntity<BookingResponse> advance(@PathVariable Long id) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id must be a positive integer");
        }
        Booking b = bookings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not found"));
        if (b.getStatus() == BookingStatus.PENDING) {
            return ResponseEntity.ok(toResponse(b));                                          // 200 — 전이 가능
        }
        if (b.getStatus() == BookingStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "booking already advanced"); // 409
        }
        if (b.getStatus() == BookingStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.GONE, "booking is cancelled");         // 410
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "unknown state");
    }

    private static BookingResponse toResponse(Booking b) {
        return new BookingResponse(b.getId(), b.getCustomerEmail(), b.getNights(), b.getLoyaltyPoints(),
                b.getTier().name(), b.getStatus().name(), b.getCheckInDate().toString());
    }
}
