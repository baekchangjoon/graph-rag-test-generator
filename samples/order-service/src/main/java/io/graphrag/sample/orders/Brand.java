package io.graphrag.sample.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 참조-id 픽스처(spec §5-3): BrandConverter가 PK(id)로 조회. 백업 테이블 "brands". */
@Entity
@Table(name = "brands")
public class Brand {

    @Id
    private Long id;

    @Column(nullable = false)
    private String label;

    protected Brand() {
    }

    public Brand(Long id, String label) {
        this.id = id;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }
}
