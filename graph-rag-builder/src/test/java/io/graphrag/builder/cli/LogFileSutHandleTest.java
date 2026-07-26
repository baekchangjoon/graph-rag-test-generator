package io.graphrag.builder.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-014 회귀 — {@link BuilderCli.LogFileSutHandle}의 byte-오프셋 로그 슬라이싱이 "byte 정합"
 * 계약(다른 SUT 로그 캡처 경로, 예: {@code SutProcess.sliceUtf8}과 동일 규약 — {@code logOffset()}이
 * byte 길이를 주므로 byte 단위로 잘라야 함)을 실제로 지키는지 검증한다. 비-ASCII(한글) 콘텐츠로
 * char-index 슬라이싱과 byte-index 슬라이싱이 갈리는 지점을 실측한다 — char 기반으로 잘못
 * 구현됐다면 한글 문자 중간에서 끊기거나(멀티바이트 UTF-8 디코드 오류) 오프셋이 밀린다.
 */
class LogFileSutHandleTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("REQ-014: logOffset()은 파일의 byte 크기(문자 수 아님)를 반환한다 — 한글 포함")
    void logOffsetReturnsByteSizeNotCharCount() throws Exception {
        // "안" 1글자 = UTF-8 3바이트. "B" 1글자 = 1바이트. 문자 수=2, byte 수=4.
        String content = "안B";
        Path logFile = tempDir.resolve("sut.log");
        Files.write(logFile, content.getBytes(StandardCharsets.UTF_8));

        BuilderCli.LogFileSutHandle handle = new BuilderCli.LogFileSutHandle("http://fake", logFile);

        assertThat(handle.logOffset())
                .as("logOffset()은 Files.size()와 동일한 byte 단위여야 한다(문자 수 2가 아니라 byte 수 4)")
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("REQ-014: readLogRange(byte시작, byte끝)는 한글 경계를 넘지 않는 byte 오프셋에서 정확히 잘라 UTF-8로 디코드한다")
    void readLogRangeSlicesByByteOffsetAcrossMultibyteContent() throws Exception {
        // "가"(3바이트) + "나"(3바이트) + "MARK"(4바이트, ASCII) = 총 10바이트.
        String korean = "가나";
        String marker = "MARK";
        String content = korean + marker;
        int koreanByteLen = korean.getBytes(StandardCharsets.UTF_8).length;   // 6
        Path logFile = tempDir.resolve("sut.log");
        Files.write(logFile, content.getBytes(StandardCharsets.UTF_8));

        BuilderCli.LogFileSutHandle handle = new BuilderCli.LogFileSutHandle("http://fake", logFile);

        // 전체 구간(0, logOffset())은 원문과 완전히 동일해야 한다(한글 포함 round-trip).
        assertThat(handle.readLogRange(0, handle.logOffset())).isEqualTo(content);

        // "MARK"의 시작 byte 오프셋(6)에서 끝까지 자르면 정확히 "MARK"만 나와야 한다 — 한글 6바이트를
        // 건너뛰는 지점이 char-index(2)가 아니라 byte-index(6)여야만 성립하는 단언이다. 잘못 구현돼
        // char-index로 슬라이싱했다면 byte[6:10]이 아니라 byte[2:...] 근방을 잘라 한글 중간에서
        // 끊긴 깨진 문자열이 나오거나 완전히 다른 내용이 나온다.
        assertThat(handle.readLogRange(koreanByteLen, handle.logOffset())).isEqualTo(marker);

        // 한글 첫 글자만(byte 0~3)도 정확히 디코드되어야 한다.
        assertThat(handle.readLogRange(0, 3)).isEqualTo("가");
    }

    @Test
    @DisplayName("REQ-014: readLogRange는 범위를 [0, 파일길이]로 클리핑한다(파일 크기 초과·음수 모두)")
    void readLogRangeClipsOutOfBoundsRange() throws Exception {
        String content = "hello";   // 5 bytes
        Path logFile = tempDir.resolve("sut.log");
        Files.write(logFile, content.getBytes(StandardCharsets.UTF_8));
        BuilderCli.LogFileSutHandle handle = new BuilderCli.LogFileSutHandle("http://fake", logFile);

        assertThat(handle.readLogRange(-10, 1000))
                .as("음수 시작 + 파일 크기 초과 끝은 [0, len]으로 클리핑되어 전체를 반환해야 한다")
                .isEqualTo("hello");
        assertThat(handle.readLogRange(2, 1000))
                .as("끝이 파일 크기를 초과하면 파일 끝으로 클리핑되어야 한다")
                .isEqualTo("llo");
        assertThat(handle.readLogRange(10, 20))
                .as("시작이 파일 크기를 넘으면(from>=to 클램프 후) 빈 문자열이어야 한다")
                .isEqualTo("");
    }

    @Test
    @DisplayName("REQ-014: 로그 파일 미지정(null) 또는 미존재 시 logOffset은 0, readLogRange/readLog는 빈 문자열")
    void nullOrMissingLogFileYieldsEmptyLog() {
        BuilderCli.LogFileSutHandle nullHandle = new BuilderCli.LogFileSutHandle("http://fake", null);
        assertThat(nullHandle.logOffset()).isEqualTo(0L);
        assertThat(nullHandle.readLogRange(0, 100)).isEmpty();
        assertThat(nullHandle.readLog()).isEmpty();
        assertThat(nullHandle.readLogFrom(0)).isEmpty();

        BuilderCli.LogFileSutHandle missingHandle =
                new BuilderCli.LogFileSutHandle("http://fake", tempDir.resolve("does-not-exist.log"));
        assertThat(missingHandle.logOffset()).isEqualTo(0L);
        assertThat(missingHandle.readLogRange(0, 100)).isEmpty();
    }

    @Test
    @DisplayName("REQ-014: readLog()/readLogFrom(offset)는 각각 전체 구간/offset부터 끝까지에 위임한다")
    void readLogAndReadLogFromDelegateToFullOrTailRange() throws Exception {
        String content = "가나MARK";
        Path logFile = tempDir.resolve("sut.log");
        Files.write(logFile, content.getBytes(StandardCharsets.UTF_8));
        BuilderCli.LogFileSutHandle handle = new BuilderCli.LogFileSutHandle("http://fake", logFile);

        assertThat(handle.readLog()).isEqualTo(content);
        int koreanByteLen = "가나".getBytes(StandardCharsets.UTF_8).length;
        assertThat(handle.readLogFrom(koreanByteLen)).isEqualTo("MARK");
    }
}
