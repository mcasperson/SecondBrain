package secondbrain.domain.converter;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Without any extractors, files are read as plain text.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(FileToTextContext.class)
class FileToTextContextNoExtractorsTest {

	// Assigned by JUnit before each test
	@TempDir
	@SuppressWarnings("NullAway.Init")
	Path tempDir;

	@Inject
	private FileToText fileToText;

	@Test
	void convertReadsTheFileAsText() throws IOException {
		final Path file = tempDir.resolve("notes.txt");
		Files.writeString(file, "plain text");

		assertEquals("plain text", fileToText.convert(file.toString()));
	}

	@Test
	void convertReturnsNullForAMissingFile() {
		assertNull(fileToText.convert(tempDir.resolve("missing.txt").toString()));
	}

	@Test
	void convertByteArrayReturnsTheBytesAsText() {
		assertEquals("bytes", fileToText.convertByteArray("bytes".getBytes(StandardCharsets.UTF_8)));
	}
}
