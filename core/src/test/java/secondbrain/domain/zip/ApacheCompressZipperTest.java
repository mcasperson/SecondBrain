package secondbrain.domain.zip;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ApacheCompressZipperTest {
    private final ApacheCompressZipper zipper = new ApacheCompressZipper();

    @Test
    void compressAndDecompressString_roundTrip() {
        String original = "Hello, SecondBrain!";
        byte[] compressed = zipper.compressString(original);
        String decompressed = zipper.decompressBytes(compressed);
        assertEquals(original, decompressed);
    }

    @Test
    void compressString_emptyOrNull() {
        assertArrayEquals(new byte[0], zipper.compressString(""));
        assertArrayEquals(new byte[0], zipper.compressString(null));
    }

    @Test
    void decompressBytes_emptyOrNull() {
        assertEquals("", zipper.decompressBytes(new byte[0]));
        assertEquals("", zipper.decompressBytes(null));
    }

    @Test
    void decompressBytes_invalidData() {
        // Should not throw, may return empty or garbage
        byte[] invalid = {1, 2, 3, 4, 5};
        assertThrows(IOException.class, () -> zipper.decompressBytes(invalid));
    }
}

