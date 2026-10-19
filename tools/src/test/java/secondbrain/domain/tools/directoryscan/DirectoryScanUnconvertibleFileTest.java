package secondbrain.domain.tools.directoryscan;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.converter.FileToTextContext;
import secondbrain.domain.converter.TikaTextExtractor;
import secondbrain.domain.debug.DebugToolArgsKeyValue;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.files.AntPathMatcher;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.StubSentenceVectorizer;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Uses the real Tika extractor, which throws when a file can not be parsed, to show that one unconvertible file
 * is skipped rather than preventing the rest of the directory from being scanned.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(DirectoryScan.class)
@AddBeanClasses(DirectoryScanConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(DebugToolArgsKeyValue.class)
@AddBeanClasses(FileToTextContext.class)
@AddBeanClasses(TikaTextExtractor.class)
@AddBeanClasses(AntPathMatcher.class)
@AddBeanClasses(Loggers.class)
// The keywords tool is replaced by a mock from a producer method
@ExcludeBeanClasses(Keywords.class)
class DirectoryScanUnconvertibleFileTest {
	private static final String PROMPT = "Which files mention Acme?";
	// A gzip header followed by garbage, which Tika fails to decompress
	private static final byte[] CORRUPT_GZIP = {0x1f, (byte) 0x8b, 8, 0, 1, 2, 3, 4, 5, 6, 7};

	private final RecordingLlmClient llmClient = new RecordingLlmClient()
			.respondWith(doc -> "summary of " + doc.getIndividualContexts().getFirst().document().strip());

	// Assigned by JUnit before each test
	@TempDir
	@SuppressWarnings("NullAway.Init")
	Path dir;

	@Inject
	private DirectoryScan tool;

	@Produces
	@Preferred
	LlmClient produceLlmClient() {
		return llmClient;
	}

	@Produces
	@Preferred
	LocalStorage produceLocalStorage() {
		return new RecordingLocalStorage();
	}

	@Produces
	SentenceVectorizer produceSentenceVectorizer() {
		return new StubSentenceVectorizer();
	}

	@Produces
	Keywords produceKeywords() {
		return mock(Keywords.class);
	}

	@Produces
	ToStringGenerator produceToStringGenerator() {
		return TestArgs.toStringGenerator("directory-scan", 3);
	}

	@BeforeEach
	void setUp() throws IOException {
		TestConfigUtil.registerConfig(Map.of());
		Files.writeString(dir.resolve("a.txt"), "Acme renewed their contract.");
		Files.write(dir.resolve("corrupt.gz"), CORRUPT_GZIP);
	}

	@ParameterizedTest
	@ValueSource(strings = {"true", "false"})
	void getContextSkipsAFileThatCanNotBeConvertedAndKeepsTheOthers(final String summarize) {
		final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), List.of(
				new ToolArgs(DirectoryScan.DIRECTORYSCAN_DIRECTORY, dir.toString(), true),
				new ToolArgs(CommonArguments.SUMMARIZE_DOCUMENT_ARG, summarize, true)));

		assertEquals(List.of(dir.resolve("a.txt").toString()), context.stream().map(RagDocumentContext::getId).toList());
		assertEquals(Boolean.parseBoolean(summarize) ? "summary of Acme renewed their contract." : "Acme renewed their contract.",
				context.getFirst().document().strip());
	}
}
