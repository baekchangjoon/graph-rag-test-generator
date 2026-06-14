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

    private static BookingResponse toResponse(Booking b) {
        return new BookingResponse(b.getId(), b.getCustomerEmail(), b.getNights(), b.getLoyaltyPoints(),
                b.getTier().name(), b.getStatus().name(), b.getCheckInDate().toString());
    }
}
