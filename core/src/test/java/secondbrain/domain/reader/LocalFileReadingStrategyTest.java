package secondbrain.domain.reader;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import secondbrain.domain.exceptions.InvalidFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(LocalFileReadingStrategy.class)
class LocalFileReadingStrategyTest {

	// Assigned by JUnit before each test
	@TempDir
	@SuppressWarnings("NullAway.Init")
	Path tempDir;

	@Inject
	private LocalFileReadingStrategy strategy;

	@Test
	void isSupportedForExistingRegularFile() throws IOException {
		final Path file = Files.writeString(tempDir.resolve("doc.txt"), "content");

		assertTrue(strategy.isSupported(file.toString()));
	}

	@Test
	void isSupportedForEmptyRegularFile() throws IOException {
		final Path file = Files.createFile(tempDir.resolve("empty.txt"));

		assertTrue(strategy.isSupported(file.toString()));
	}

	@Test
	void isNotSupportedForDirectory() {
		assertFalse(strategy.isSupported(tempDir.toString()));
	}

	@Test
	void isNotSupportedForMissingFile() {
		assertFalse(strategy.isSupported(tempDir.resolve("missing.txt").toString()));
	}

	@Test
	void isNotSupportedForUrl() {
		assertFalse(strategy.isSupported("https://example.org/doc.txt"));
	}

	@Test
	void priorityIs200() {
		assertEquals(200, strategy.getPriority());
	}

	@Test
	void readReturnsUtf8FileContents() throws IOException {
		final String content = "first line\nzweite Zeile: äöü ✓";
		final Path file = Files.writeString(tempDir.resolve("doc.txt"), content, StandardCharsets.UTF_8);

		assertEquals(content, strategy.read(file.toString()));
	}

	@Test
	void readReturnsEmptyStringForEmptyFile() throws IOException {
		final Path file = Files.createFile(tempDir.resolve("empty.txt"));

		assertEquals("", strategy.read(file.toString()));
	}

	@Test
	void readThrowsInvalidFileForMissingFile() {
		final String path = tempDir.resolve("missing.txt").toString();

		final InvalidFile error = assertThrows(InvalidFile.class, () -> strategy.read(path));
		assertEquals("Failed to read document from " + path, error.getMessage());
	}

	@Test
	void readThrowsInvalidFileForDirectory() {
		final String path = tempDir.toString();

		final InvalidFile error = assertThrows(InvalidFile.class, () -> strategy.read(path));
		assertEquals("Failed to read document from " + path, error.getMessage());
	}
}
