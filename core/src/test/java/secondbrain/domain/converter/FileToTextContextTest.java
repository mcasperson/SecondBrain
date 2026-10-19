package secondbrain.domain.converter;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
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

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(FileToTextContext.class)
class FileToTextContextTest {

	// Assigned by JUnit before each test
	@TempDir
	@SuppressWarnings("NullAway.Init")
	Path tempDir;

	@Inject
	private FileToText fileToText;

	/*
		The extractors are produced in an order that differs from their priority, so the selection can not
		depend on the order they are returned by CDI.
	 */
	@Produces
	TextExtractorStrategy produceFallbackExtractor() {
		return new FixedExtractor("fallback", Integer.MAX_VALUE, "");
	}

	@Produces
	TextExtractorStrategy produceGeneralExtractor() {
		return new FixedExtractor("general", 10, ".pdf");
	}

	@Produces
	TextExtractorStrategy produceUnsupportedLowPriorityExtractor() {
		return new FixedExtractor("csv", 1, ".csv");
	}

	@Produces
	TextExtractorStrategy produceSpecificExtractor() {
		return new FixedExtractor("specific", 5, ".pdf");
	}

	@Test
	void convertUsesTheSupportingExtractorWithTheLowestPriorityNumber() {
		assertEquals("specific:report.pdf", fileToText.convert("report.pdf"));
	}

	@Test
	void convertSkipsLowerPriorityExtractorsThatDoNotSupportThePath() {
		// "csv" has the lowest number but does not support .txt, so the fallback is used
		assertEquals("fallback:notes.txt", fileToText.convert("notes.txt"));
	}

	@Test
	void convertUsesTheLowestPriorityExtractorWhenItSupportsThePath() {
		assertEquals("csv:data.csv", fileToText.convert("data.csv"));
	}

	@Test
	void convertByteArrayUsesTheExtractorWithTheLowestPriorityNumber() {
		assertEquals("csv:bytes", fileToText.convertByteArray("bytes".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void convertDoesNotReadTheFileItselfWhenAnExtractorSupportsIt() throws IOException {
		final Path file = tempDir.resolve("report.pdf");
		Files.writeString(file, "raw file contents");

		assertEquals("specific:" + file, fileToText.convert(file.toString()));
	}

	/**
	 * Supports paths ending with a suffix (an empty suffix supports every path), and returns its name and the input.
	 */
	private record FixedExtractor(String name, int priority, String suffix) implements TextExtractorStrategy {
		@Override
		public String convert(final String path) {
			return name + ":" + path;
		}

		@Override
		public String convertContents(final byte[] contents) {
			return name + ":" + new String(contents, StandardCharsets.UTF_8);
		}

		@Override
		public boolean isSupported(final String path) {
			return path.endsWith(suffix);
		}
	}
}
