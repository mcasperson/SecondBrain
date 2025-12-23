package secondbrain.domain.zip;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.logger.Loggers;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ApacheCompressZipper.class)
@AddBeanClasses(Loggers.class)
class ApacheCompressZipperTest {
    @Inject
    private ApacheCompressZipper zipper;

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

