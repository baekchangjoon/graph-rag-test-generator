package io.graphrag.sample.orders;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 참조-id 변환(spec §5-3): PK 문자열 토큰 → Brand 엔티티(name이 아닌 PK 요구). @Component 자동 등록.
 * 러너의 name 1순위 후보(label)는 convert 실패 → PK 2순위 후보(id)로 backtrack해야 성공 arm 도달.
 */
@Component
public class BrandConverter implements Converter<String, Brand> {

    private final BrandRepository brands;

    public BrandConverter(BrandRepository brands) {
        this.brands = brands;
    }

    @Override
    public Brand convert(String source) {
        try {
            return brands.findById(Long.parseLong(source.trim())).orElse(null);
        } catch (NumberFormatException ex) {
            return null;   // name 토큰 등 비-PK → 바인딩 실패(러너가 PK 후보로 backtrack)
        }
    }
}
