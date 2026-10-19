package secondbrain.domain.converter;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import secondbrain.domain.exceptions.ExternalFailure;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(TikaTextExtractor.class)
class TikaTextExtractorTest {
    /**
     * A gzip header followed by a truncated body, which Tika detects as gzip and then fails to decompress
     * (AutoDetectParser throws TikaException for this input).
     */
    private static final byte[] CORRUPT_GZIP = {0x1f, (byte) 0x8b, 8, 0, 1, 2, 3, 4, 5, 6, 7};

    // Assigned by JUnit before each test
    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path tempDir;

    @Inject
    private TikaTextExtractor extractor;

    @Test
    void convertExtractsTextFromPlainTextFile() throws IOException {
        final Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "First line\nSecond line");

        final String text = extractor.convert(file.toString());

        assertEquals("First line\nSecond line", text.strip());
    }

    @Test
    void convertStripsMarkupFromHtmlFile() throws IOException {
        final Path file = tempDir.resolve("page.html");
        Files.writeString(file, "<html><head><title>Title</title></head><body><p>Hello <b>world</b></p></body></html>");

        final String text = extractor.convert(file.toString());

        assertTrue(text.contains("Hello world"), text);
        assertFalse(text.contains("<p>"), text);
        assertFalse(text.contains("<b>"), text);
    }

    @Test
    void convertThrowsWhenFileDoesNotExist() {
        final String missing = tempDir.resolve("missing.txt").toString();

        assertThrows(FileNotFoundException.class, () -> extractor.convert(missing));
    }

    @Test
    void convertReturnsBlankTextForEmptyFile() throws IOException {
        final Path file = tempDir.resolve("empty.txt");
        Files.write(file, new byte[0]);

        assertEquals("", extractor.convert(file.toString()).strip());
    }

    @Test
    void convertThrowsExternalFailureWhenParserFails() throws IOException {
        final Path file = tempDir.resolve("corrupt.gz");
        Files.write(file, CORRUPT_GZIP);

        final ExternalFailure ex = assertThrows(ExternalFailure.class, () -> extractor.convert(file.toString()));

        assertEquals("Failed to parse file", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void convertContentsExtractsTextFromBytes() {
        final String text = extractor.convertContents("plain bytes content".getBytes(StandardCharsets.UTF_8));

        assertEquals("plain bytes content", text.strip());
    }

    @Test
    void convertContentsStripsMarkupFromHtmlBytes() {
        final String text = extractor.convertContents(
                "<html><body><h1>Heading</h1><p>Paragraph</p></body></html>".getBytes(StandardCharsets.UTF_8));

        assertTrue(text.contains("Heading"), text);
        assertTrue(text.contains("Paragraph"), text);
        assertFalse(text.contains("<h1>"), text);
    }

    @Test
    void convertContentsReturnsBlankTextForEmptyBytes() {
        assertTrue(extractor.convertContents(new byte[0]).isBlank());
    }

    @Test
    void convertContentsThrowsExternalFailureWhenParserFails() {
        final ExternalFailure ex = assertThrows(ExternalFailure.class, () -> extractor.convertContents(CORRUPT_GZIP));

        assertEquals("Failed to parse string", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void everyPathIsSupported() {
        assertTrue(extractor.isSupported("file.pdf"));
        assertTrue(extractor.isSupported("no-extension"));
        assertTrue(extractor.isSupported(""));
    }

    @Test
    void priorityIsLowestSoSpecificExtractorsWin() {
        assertEquals(Integer.MAX_VALUE, extractor.priority());
    }
}
