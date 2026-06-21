package io.graphrag.sample.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 참조-name 픽스처(spec §5-2): ColorFormatter가 name으로 조회. 백업 테이블 "colors". */
@Entity
@Table(name = "colors")
public class Color {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    protected Color() {
    }

    public Color(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
