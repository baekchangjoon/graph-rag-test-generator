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
        private boolean active;
        private String note;
        private int count;
        private int balance;
        private double rate;

        Long getId() { return id; }
        LocalDate getCheckInDate() { return checkInDate; }
        BookingStatus getStatus() { return status; }
        boolean getActive() { return active; }
        boolean isActive() { return active; }
        String getNote() { return note; }
        int getCount() { return count; }
        int getBalance() { return balance; }
        double getRate() { return rate; }
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

    /** 다중 전이(작업 #5): 각 상태 명시 == → positiveConstants={PENDING,CONFIRMED,CANCELLED}. */
    String advance(Booking b) {
        if (b.getStatus() == BookingStatus.PENDING) {                       // EQ state guard
            return "confirmed";
        }
        if (b.getStatus() == BookingStatus.CONFIRMED) {                     // EQ state guard
            return "conflict";
        }
        if (b.getStatus() == BookingStatus.CANCELLED) {                     // EQ state guard
            return "gone";
        }
        return "unknown";
    }

    /** NE+EQ 혼합 컬럼((viii)): status != PENDING && status == CONFIRMED → negated=[PENDING]·positive=[CONFIRMED]. */
    String mixed(Booking b) {
        if (b.getStatus() != BookingStatus.PENDING && b.getStatus() == BookingStatus.CONFIRMED) {
            return "x";
        }
        return "y";
    }

    /** BOOLEAN truthy 단독 getter(getActive) — 저장 행 active 컬럼, comparand="true". */
    String byActive(Booking b) {
        if (b.getActive()) {
            return "on";
        }
        return "off";
    }

    /** BOOLEAN NOT 래핑 is-prefix getter(isActive) — comparand="false". */
    String byNotActive(Booking b) {
        if (!b.isActive()) {
            return "off";
        }
        return "on";
    }

    /** BOOLEAN is-prefix getter 단독(isActive) — column="active", comparand="true". */
    String byIsActive(Booking b) {
        if (b.isActive()) {
            return "on";
        }
        return "off";
    }

    /** NULLITY == null: getter() == null → kind=NULLITY, column="note", op="==", comparand="null". */
    String byNote(Booking b) {
        if (b.getNote() == null) {
            return "empty";
        }
        return "has";
    }

    /** NULLITY != null: getter() != null → kind=NULLITY, column="note", op="!=", comparand="null". */
    String byNoteNe(Booking b) {
        if (b.getNote() != null) {
            return "has";
        }
        return "empty";
    }

    /** NUMERIC gt: getter() > 정수리터럴 → kind=NUMERIC, column="count", op=">", comparand="0". */
    String byCount(Booking b) {
        if (b.getCount() > 0) {
            return "pos";
        }
        return "nonpos";
    }

    /** NUMERIC ge 음수리터럴: getter() >= -5 → kind=NUMERIC, column="balance", op=">=", comparand="-5". */
    String byBalance(Booking b) {
        if (b.getBalance() >= -5) {
            return "ok";
        }
        return "low";
    }

    /** NUMERIC float 제외: getter() > 1.5 → double 리터럴 → NUMERIC emit 안 함. */
    String byRate(Booking b) {
        if (b.getRate() > 1.5) {
            return "hi";
        }
        return "lo";
    }
}
