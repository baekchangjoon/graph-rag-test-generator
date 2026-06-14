package io.graphrag.sample.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * by-id(PUT/DELETE/GET /{id}) + enum(@Enumerated STRING) + LocalDate + 이메일 + 다필드 가드를
 * 한 리소스로 모아 CI(order-service e2e)가 Stage 0/1/2/3/3b 수정을 회귀로 보호하게 한다.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(nullable = false)
    private int nights;

    @Column(name = "loyalty_points", nullable = false)
    private int loyaltyPoints;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    protected Booking() {
    }

    public Booking(String customerEmail, int nights, int loyaltyPoints, BookingTier tier,
                   BookingStatus status, LocalDate checkInDate) {
        this.customerEmail = customerEmail;
        this.nights = nights;
        this.loyaltyPoints = loyaltyPoints;
        this.tier = tier;
        this.status = status;
        this.checkInDate = checkInDate;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public int getNights() {
        return nights;
    }

    public void setNights(int nights) {
        this.nights = nights;
    }

    public int getLoyaltyPoints() {
        return loyaltyPoints;
    }

    public BookingTier getTier() {
        return tier;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public LocalDate getCheckInDate() {
        return checkInDate;
    }
}
