package secondbrain.domain.zip;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.logger.Loggers;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ApacheCommonsZStdZipper.class)
@AddBeanClasses(Loggers.class)
class ApacheCommonsZStdZipperTest {
    @Inject
    @Identifier("ApacheCommonsZStdZipper")
    private ApacheCommonsZStdZipper zipper;

    @Test
    void compressAndDecompressString_roundTrip() {
        String original = "Hello, SecondBrain!";
        String compressed = zipper.compressString(original);
        String decompressed = zipper.decompressString(compressed);
        assertEquals(original, decompressed);
    }

    @Test
    void compressAndDecompressString_largeInput() {
        String original = "SecondBrain ".repeat(10_000);
        String compressed = zipper.compressString(original);
        assertNotNull(compressed);
        // ZStd should compress repetitive data significantly
        assertTrue(compressed.length() < original.length());
        String decompressed = zipper.decompressString(compressed);
        assertEquals(original, decompressed);
    }

    @Test
    void compressString_emptyOrNull() {
        assertNull(zipper.compressString(""));
        assertNull(zipper.compressString(null));
    }

    @Test
    void decompressString_emptyOrNull() {
        assertNull(zipper.decompressString(""));
        assertNull(zipper.decompressString(null));
    }

    @Test
    void decompressString_invalidData() {
        // Invalid Base64 / non-ZStd payload should throw
        assertThrows(Exception.class, () -> zipper.decompressString("notvalidbase64!!"));
    }

    @Test
    void compressString_producesBase64Output() {
        String compressed = zipper.compressString("test data");
        assertNotNull(compressed);
        // Base64 characters only
        assertTrue(compressed.matches("[A-Za-z0-9+/=]+"));
    }

    @Test
    void compressString_differentInputProducesDifferentOutput() {
        String a = zipper.compressString("hello world");
        String b = zipper.compressString("goodbye world");
        assertNotEquals(a, b);
    }
}

