package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JsonMappersTest {

    record SampleRecord(String myField, int someValue, Instant occurredAt) {}

    @Test
    void serializesRecordWithSnakeCaseFieldNames() throws Exception {
        ObjectMapper mapper = JsonMappers.standard();
        SampleRecord r = new SampleRecord("hello", 7, Instant.parse("2026-05-25T10:30:00Z"));

        String json = mapper.writeValueAsString(r);

        assertThat(json).contains("\"my_field\":\"hello\"");
        assertThat(json).contains("\"some_value\":7");
        assertThat(json).contains("\"occurred_at\":\"2026-05-25T10:30:00Z\"");
    }

    @Test
    void deserializesSnakeCaseIntoRecord() throws Exception {
        ObjectMapper mapper = JsonMappers.standard();
        String json = "{\"my_field\":\"world\",\"some_value\":42,"
                + "\"occurred_at\":\"2026-05-25T10:30:00Z\"}";

        SampleRecord r = mapper.readValue(json, SampleRecord.class);

        assertThat(r.myField()).isEqualTo("world");
        assertThat(r.someValue()).isEqualTo(42);
        assertThat(r.occurredAt()).isEqualTo(Instant.parse("2026-05-25T10:30:00Z"));
    }

    @Test
    void roundTripPreservesRecordEquality() throws Exception {
        ObjectMapper mapper = JsonMappers.standard();
        SampleRecord original = new SampleRecord("rt", 99, Instant.parse("2026-01-01T00:00:00Z"));

        String json = mapper.writeValueAsString(original);
        SampleRecord back = mapper.readValue(json, SampleRecord.class);

        assertThat(back).isEqualTo(original);
    }

    @Test
    void ignoresUnknownFieldsOnDeserialization() throws Exception {
        ObjectMapper mapper = JsonMappers.standard();
        String json = "{\"my_field\":\"x\",\"some_value\":1,"
                + "\"occurred_at\":\"2026-05-25T10:30:00Z\","
                + "\"future_field\":\"ignored\"}";

        SampleRecord r = mapper.readValue(json, SampleRecord.class);

        assertThat(r.myField()).isEqualTo("x");
    }
}
