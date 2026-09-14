package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Utf8SseLineReaderTest {
    @Test
    void reportsExactTransportBytesForLfCrLfAndFinalUnterminatedLine() throws Exception {
        byte[] input = "one\ntwo\r\nthree".getBytes(StandardCharsets.UTF_8);
        try (Utf8SseLineReader reader = new Utf8SseLineReader(new ByteArrayInputStream(input))) {
            assertThat(reader.readLine(16)).isEqualTo(new Utf8SseLineReader.Line("one", 4));
            assertThat(reader.readLine(16)).isEqualTo(new Utf8SseLineReader.Line("two", 5));
            assertThat(reader.readLine(16)).isEqualTo(new Utf8SseLineReader.Line("three", 5));
            assertThat(reader.readLine(16)).isNull();
        }
    }

    @Test
    void recognizesStandaloneCrAsAnSseLineTerminator() throws Exception {
        byte[] input = "one\rtwo\r\nthree\nfour".getBytes(StandardCharsets.UTF_8);
        try (Utf8SseLineReader reader = new Utf8SseLineReader(new ByteArrayInputStream(input))) {
            assertThat(reader.readLine(16)).isEqualTo(new Utf8SseLineReader.Line("one", 4));
            assertThat(reader.readLine(16)).isEqualTo(new Utf8SseLineReader.Line("two", 5));
            assertThat(reader.readLine(16)).isEqualTo(new Utf8SseLineReader.Line("three", 6));
            assertThat(reader.readLine(16)).isEqualTo(new Utf8SseLineReader.Line("four", 4));
            assertThat(reader.readLine(16)).isNull();
        }
    }

    @Test
    void rejectsAByteOversizedLineBeforeConstructingItsString() throws Exception {
        byte[] input = "12345\n".getBytes(StandardCharsets.UTF_8);
        try (Utf8SseLineReader reader = new Utf8SseLineReader(new ByteArrayInputStream(input))) {
            assertThatThrownBy(() -> reader.readLine(4))
                    .isInstanceOf(Utf8SseLineReader.LineLimitExceededException.class)
                    .hasMessageContaining("transport byte limit");
        }
    }
}
