package secondbrain.domain.answer;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.constants.ModelRegex;
import secondbrain.domain.test.TestConfigUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultAnswerFormatterService}. The formatters are the real Deepseek formatter, plus a
 * formatter that upper cases answers for the model "upper", plus a formatter with an invalid regex.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(DefaultAnswerFormatterService.class)
@AddBeanClasses(AnswerFormatterDeepseek.class)
class DefaultAnswerFormatterServiceTest {
	private final List<String> warnings = new ArrayList<>();

	@Inject
	private DefaultAnswerFormatterService service;

	@Produces
	AnswerFormatter produceUpperCaseFormatter() {
		return new AnswerFormatter() {
			@Override
			public String modelRegex() {
				return "upper";
			}

			@Override
			public String formatAnswer(final String answer) {
				return answer.toUpperCase(Locale.ROOT);
			}
		};
	}

	@Produces
	AnswerFormatter produceInvalidRegexFormatter() {
		return new AnswerFormatter() {
			@Override
			public String modelRegex() {
				return "(";
			}

			@Override
			public String formatAnswer(final String answer) {
				return "invalid formatter used";
			}
		};
	}

	@Produces
	Logger produceLogger() {
		final Logger logger = Logger.getAnonymousLogger();
		logger.setUseParentHandlers(false);
		logger.addHandler(new Handler() {
			@Override
			public void publish(final LogRecord record) {
				warnings.add(record.getMessage());
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		});
		return logger;
	}

	@BeforeEach
	void updateConfig() {
		TestConfigUtil.registerConfig(Map.of("sb.answerformatter.deepseekregex", ModelRegex.DEEPSEEK_REGEX));
	}

	@Test
	void formatsWithTheFormatterWhoseRegexMatchesTheModel() {
		assertEquals("HELLO WORLD", service.formatResponse("upper", "hello world"));
	}

	@Test
	void formatsWithTheConfiguredDeepseekFormatter() {
		assertEquals("The answer.", service.formatResponse("deepseek-r1", "<think>reasoning</think> The answer."));
	}

	@Test
	void theRegexMustMatchTheWholeModelName() {
		assertEquals("hello world", service.formatResponse("my-upper-model", "hello world"));
	}

	@Test
	void returnsTheResponseUnchangedWhenNoFormatterMatches() {
		assertEquals("<think>kept</think> answer", service.formatResponse("plain", "<think>kept</think> answer"));
	}

	@Test
	void anInvalidRegexIsTreatedAsNoMatchAndLogged() {
		assertEquals("answer", service.formatResponse("plain", "answer"));

		assertEquals(1, warnings.size());
		assertTrue(warnings.getFirst().startsWith("Invalid regex for model plain: (. Error: "), warnings.getFirst());
	}
}
