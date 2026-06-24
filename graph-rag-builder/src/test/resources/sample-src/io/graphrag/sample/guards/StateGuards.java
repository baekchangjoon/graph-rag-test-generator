package io.graphrag.sample.guards;

import java.time.LocalDate;

/**
 * 상태 의존 가드(저장된 단일 행 상태로 분기) 픽스처 — Stage 4 StateGuardOracle 인식 대상.
 * order-service BookingController의 GET stale / DELETE conflict 가드를 축약 미러.
 */
public class StateGuards {

    public enum BookingStatus { PENDING, CONFIRMED, CANCELLED }

    public enum BookingTier { BASIC, VIP }

    /** JPA 엔티티 형태(getter) — fieldRef가 getCheckInDate→checkInDate, getStatus→status로 정규화. */
    static class Booking {
        private Long id;
        private LocalDate checkInDate;
        private BookingStatus status;
        private BookingTier tier;
        private boolean active;
        private String note;
        private int count;
        private int balance;
        private double rate;
        private int nights;

        Long getId() { return id; }
        LocalDate getCheckInDate() { return checkInDate; }
        BookingStatus getStatus() { return status; }
        BookingTier getTier() { return tier; }
        boolean getActive() { return active; }
        boolean isActive() { return active; }
        String getNote() { return note; }
        int getCount() { return count; }
        int getBalance() { return balance; }
        double getRate() { return rate; }
        int getNights() { return nights; }
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

    /** NUMERIC-vs-PARAM 직접참조: getter() >= paramRef → kind=NUMERIC, comparandKind=PARAM, comparand="minNights". */
    String byNightsParam(Booking b, int minNights) {
        if (b.getNights() >= minNights) {
            return "ok";
        }
        return "below";
    }

    /** NUMERIC-vs-PARAM 중간계산 제외: getter() >= m*2 → CtBinaryOperator → NUMERIC emit 안 함. */
    String byCalc(Booking b, int m) {
        if (b.getNights() >= m * 2) {
            return "ok";
        }
        return "below";
    }

    // ── 복합 AND conjunction 픽스처 (StateGuardConjunction 검출 대상) ──

    /** 2-leaf ENUM+ENUM conjunction: status==CONFIRMED && tier==VIP → StateGuardConjunction. */
    String byStatusTier(Booking b) {
        if (b.getStatus() == BookingStatus.CONFIRMED && b.getTier() == BookingTier.VIP) {
            return "ok";
        }
        return "no";
    }

    /** TEMPORAL+BOOLEAN conjunction: isBefore(now()) && getActive() → TEMPORAL leaf 먼저 분류. */
    String byTemporalActive(Booking b) {
        if (b.getCheckInDate().isBefore(LocalDate.now()) && b.getActive()) {
            return "ok";
        }
        return "no";
    }

    /** numeric-param leaf 혼입 → conjunction skip (getActive는 BOOLEAN이지만 getNights()>=min는 PARAM leaf). */
    String byNumParam(Booking b, int min) {
        if (b.getNights() >= min && b.getActive()) {
            return "ok";
        }
        return "no";
    }

    /** OR 혼입 → conjunction skip (순수 AND 아님). */
    String byOr(Booking b) {
        if (b.getActive() && (b.getCount() > 0 || b.getNights() > 0)) {
            return "ok";
        }
        return "no";
    }

    /** 3-leaf conjunction: ENUM+BOOLEAN+NUMERIC → StateGuardConjunction(3 leaves). */
    String byThree(Booking b) {
        if (b.getStatus() == BookingStatus.CONFIRMED && b.getActive() && b.getCount() > 0) {
            return "ok";
        }
        return "no";
    }

    /** 4-leaf conjunction → skip (leaf 2~3개만 emit). */
    String byFour(Booking b) {
        if (b.getStatus() == BookingStatus.CONFIRMED && b.getTier() == BookingTier.VIP
                && b.getActive() && b.getCount() > 0) {
            return "ok";
        }
        return "no";
    }
}
