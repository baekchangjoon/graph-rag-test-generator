package io.graphrag.sample.guards;

import java.time.LocalDate;

/**
 * 상태 의존 가드(저장된 단일 행 상태로 분기) 픽스처 — Stage 4 StateGuardOracle 인식 대상.
 * order-service BookingController의 GET stale / DELETE conflict 가드를 축약 미러.
 */
public class StateGuards {

    public enum BookingStatus { PENDING, CONFIRMED, CANCELLED }

    /** JPA 엔티티 형태(getter) — fieldRef가 getCheckInDate→checkInDate, getStatus→status로 정규화. */
    static class Booking {
        private Long id;
        private LocalDate checkInDate;
        private BookingStatus status;

        Long getId() { return id; }
        LocalDate getCheckInDate() { return checkInDate; }
        BookingStatus getStatus() { return status; }
    }

    String getById(Booking b, boolean includeStale, int id) {
        if (id <= 0) {                                                      // pure-input 비교 — state guard 아님
            return "bad";
        }
        if (!includeStale && b.getCheckInDate().isBefore(LocalDate.now())) {  // TEMPORAL state guard
            return "stale";
        }
        return "ok";
    }

    String filter(BookingStatus requested) {
        if (requested != BookingStatus.CANCELLED) {   // pure-input enum(파라미터) — state guard 아님
            return "active";
        }
        return "cancelled";
    }

    String delete(Booking b, boolean confirm) {
        if (!confirm) {
            return "needs-confirm";
        }
        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() != BookingStatus.CANCELLED) {  // ENUM state guard
            return "conflict";
        }
        return "deleted";
    }
}
