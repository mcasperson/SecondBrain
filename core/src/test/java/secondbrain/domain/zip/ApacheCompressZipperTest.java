package secondbrain.domain.zip;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ApacheCompressZipperTest {
    private final ApacheCompressZipper zipper = new ApacheCompressZipper();

    @Test
    void compressAndDecompressString_roundTrip() {
        String original = "Hello, SecondBrain!";
        String compressed = zipper.compressString(original);
        String decompressed = zipper.decompressString(compressed);
        assertEquals(original, decompressed);
    }

    @Test
    void compressString_emptyOrNull() {
        assertNull(zipper.compressString(""));
        assertNull(zipper.compressString(null));
    }

    @Test
    void decompressBytes_emptyOrNull() {
        assertNull(zipper.decompressString(""));
        assertNull(zipper.decompressString(null));
    }

    @Test
    void decompressBytes_invalidData() {
        // Should not throw, may return empty or garbage
        assertThrows(IOException.class, () -> zipper.decompressString("notvalidbase64"));
    }
}

