package io.graphrag.sample.orders;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** SkuEditor의 백업 테이블(spec §5-5). 테이블명 "sku" = 러너의 camelToSnake("Sku") 해석과 일치. */
@Entity
@Table(name = "sku")
public class SkuRecord {

    @Id
    private String code;

    protected SkuRecord() {
    }

    public SkuRecord(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
