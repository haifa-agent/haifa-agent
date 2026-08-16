package io.haifa.agent.testing.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Sha256DigestsTest {
    @Test
    void hashesTextBytesFilesAndTreesDeterministically(@TempDir Path temporary) throws Exception {
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        Path file = temporary.resolve("value.txt");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);

        assertEquals(expected, Sha256Digests.bytes("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, Sha256Digests.file(file));
        assertEquals(Sha256Digests.directory(temporary), Sha256Digests.directory(temporary));
    }
}
