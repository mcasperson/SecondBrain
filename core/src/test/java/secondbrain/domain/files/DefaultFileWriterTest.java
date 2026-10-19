package secondbrain.domain.files;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.logger.Loggers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(DefaultFileWriter.class)
@AddBeanClasses(Loggers.class)
class DefaultFileWriterTest {
    // Assigned by JUnit before each test
    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path tempDir;

    @Inject
    private DefaultFileWriter fileWriter;

    @Test
    void writeStringPathCreatesFileWithContent() throws IOException {
        final Path file = tempDir.resolve("new.txt");

        fileWriter.write(file.toString(), "hello");

        assertEquals("hello", Files.readString(file));
    }

    @Test
    void writePathReplacesExistingContent() throws IOException {
        final Path file = tempDir.resolve("existing.txt");
        Files.writeString(file, "a much longer original value");

        fileWriter.write(file, "short");

        assertEquals("short", Files.readString(file));
    }

    @Test
    void writeEmptyContentTruncatesFile() throws IOException {
        final Path file = tempDir.resolve("truncate.txt");
        Files.writeString(file, "old");

        fileWriter.write(file, "");

        assertEquals("", Files.readString(file));
    }

    @Test
    void appendStringPathAddsToExistingContent() throws IOException {
        final Path file = tempDir.resolve("append.txt");
        Files.writeString(file, "first");

        fileWriter.append(file.toString(), "-second");

        assertEquals("first-second", Files.readString(file));
    }

    @Test
    void appendPathCreatesMissingFileAndAppendsOnRepeatedCalls() throws IOException {
        final Path file = tempDir.resolve("created.txt");

        fileWriter.append(file, "one");
        fileWriter.append(file, "two");

        assertEquals("onetwo", Files.readString(file));
    }

    @Test
    void writeToMissingDirectoryIsLoggedAndDoesNotThrow() {
        final Path file = tempDir.resolve("missing").resolve("file.txt");

        assertDoesNotThrow(() -> fileWriter.write(file, "content"));

        assertFalse(Files.exists(file));
    }

    @Test
    void appendToMissingDirectoryIsLoggedAndDoesNotThrow() {
        final Path file = tempDir.resolve("missing").resolve("file.txt");

        assertDoesNotThrow(() -> fileWriter.append(file.toString(), "content"));

        assertFalse(Files.exists(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void writeRejectsBlankStringPath(final String path) {
        assertThrows(IllegalArgumentException.class, () -> fileWriter.write(path, "content"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void appendRejectsBlankStringPath(final String path) {
        assertThrows(IllegalArgumentException.class, () -> fileWriter.append(path, "content"));
    }

    @Test
    @SuppressWarnings("NullAway")
    void nullArgumentsAreRejected() {
        final Path file = tempDir.resolve("null.txt");

        assertThrows(IllegalArgumentException.class, () -> fileWriter.write((String) null, "content"));
        assertThrows(NullPointerException.class, () -> fileWriter.write(file.toString(), null));
        assertThrows(NullPointerException.class, () -> fileWriter.write((Path) null, "content"));
        assertThrows(NullPointerException.class, () -> fileWriter.write(file, null));
        assertThrows(IllegalArgumentException.class, () -> fileWriter.append((String) null, "content"));
        assertThrows(NullPointerException.class, () -> fileWriter.append(file.toString(), null));
        assertThrows(NullPointerException.class, () -> fileWriter.append((Path) null, "content"));
        assertThrows(NullPointerException.class, () -> fileWriter.append(file, null));
        assertFalse(Files.exists(file));
    }
}
