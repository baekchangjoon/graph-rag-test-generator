package io.graphrag.sample.orders;

/** Booking 상태. delete 가드의 enum 비교(status != PENDING && != CANCELLED) 회귀 커버용. */
public enum BookingStatus {
    PENDING, CONFIRMED, CANCELLED
}
