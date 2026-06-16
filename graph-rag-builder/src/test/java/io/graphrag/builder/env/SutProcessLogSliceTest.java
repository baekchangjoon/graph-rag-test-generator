package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * logOffset()은 byte 길이를 주므로 readLogRange는 byte 단위로 잘라야 한다.
 * 멀티바이트 로그(비-ASCII 검증 메시지 등)에서 char 인덱스로 자르면 구간이 어긋나
 * WS/Kafka SQL 캡처가 유실되던 회귀(B1에서 발현)를 가드한다.
 */
class SutProcessLogSliceTest {

    @Test
    void sliceUtf8_byteOffsets_afterMultibyte_returnsCorrectSegment() {
        // 한국어 검증 메시지(멀티바이트) 뒤에 SQL 로그가 오는 상황.
        String prefix = "WARN 공백일 수 없습니다\n";   // 멀티바이트 포함
        String sql = "org.hibernate.SQL : select * from orders\n";
        byte[] log = (prefix + sql).getBytes(StandardCharsets.UTF_8);

        long start = prefix.getBytes(StandardCharsets.UTF_8).length;   // logOffset()이 주는 byte 오프셋
        long end = log.length;

        assertThat(SutProcess.sliceUtf8(log, start, end)).isEqualTo(sql);
    }

    @Test
    void sliceUtf8_startBeyondCharLengthButWithinByteLength_notEmpty() {
        // 회귀 핵심: byte offset이 char 길이를 초과해도(멀티바이트 때문) 빈 문자열이 되면 안 된다.
        byte[] log = "한한한SELECT".getBytes(StandardCharsets.UTF_8);   // 9 bytes(한)+6
        long start = 9;   // "한한한"의 byte 길이 = 9 (char 길이는 3)
        assertThat(SutProcess.sliceUtf8(log, start, log.length)).isEqualTo("SELECT");
    }

    @Test
    void sliceUtf8_emptyAndClamped() {
        byte[] log = "abc".getBytes(StandardCharsets.UTF_8);
        assertThat(SutProcess.sliceUtf8(log, 2, 2)).isEmpty();   // from>=to
        assertThat(SutProcess.sliceUtf8(log, 5, 9)).isEmpty();   // 범위 밖 클램프
        assertThat(SutProcess.sliceUtf8(log, 0, 99)).isEqualTo("abc");
    }
}
