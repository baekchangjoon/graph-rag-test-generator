package io.graphrag.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * shared-model 도메인 객체의 표준 JSON 직렬화 설정.
 *
 * <p>모든 컴포넌트(testlib, 도구1, 도구2, 대시보드)는 이 mapper를 사용해 일관된 JSON 표현을 보장한다.
 *
 * <ul>
 *   <li>snake_case 필드명 (Java camelCase → JSON snake_case)
 *   <li>{@link java.time.Instant} 등 java.time 지원 (ISO-8601)
 *   <li>알 수 없는 필드는 무시 (schema evolution 대비)
 *   <li>{@link java.time.Instant}를 ISO 문자열로 (Number timestamp 비활성)
 * </ul>
 */
public final class JsonMappers {

    private JsonMappers() {}

    /**
     * 도메인 객체용 표준 ObjectMapper. 새 인스턴스를 반환하므로 호출자가 추가 설정해도 안전.
     */
    public static ObjectMapper standard() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
