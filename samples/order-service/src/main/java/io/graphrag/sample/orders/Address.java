package io.graphrag.sample.orders;

/** 중첩 픽스처(spec §5-4): 컨버터 없는 POJO → Spring이 address.city/address.street 점-경로로 바인딩. */
public class Address {
    private String city;
    private String street;

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }
}
