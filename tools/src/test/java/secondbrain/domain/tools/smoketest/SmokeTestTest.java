package secondbrain.domain.tools.smoketest;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SmokeTest} and {@link SmokeTestConfig}. The ToStringGenerator returns a fixed string and
 * hash so the hash calculation can be checked exactly.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SmokeTest.class)
@AddBeanClasses(SmokeTestConfig.class)
class SmokeTestTest {
	private static final int CONFIG_HASH = 7;
	private static final List<ToolArgs> ARGS = List.of(new ToolArgs("name", "value", true));
	private static final Map<String, String> ENV = Map.of("key", "value");

	@Inject
	private SmokeTest smokeTest;

	@Inject
	private SmokeTestConfig config;

	@Produces
	ToStringGenerator produceToStringGenerator() {
		return TestArgs.toStringGenerator("config", CONFIG_HASH);
	}

	@Test
	void describesTheTool() {
		assertEquals("SmokeTest", smokeTest.getName());
		assertEquals("Used for smoke testing", smokeTest.getDescription());
		assertEquals(List.of(), smokeTest.getArguments());
		assertEquals("Unused", smokeTest.getContextLabel());
	}

	@Test
	void callRespondsToEachPrompt() {
		final RagMultiDocumentContext<Void> result = smokeTest.call(ENV, List.of("first", "second"), ARGS);

		assertEquals(List.of("Test succeeded!", "Test succeeded!"), result.getResponses());
		assertEquals("Test succeeded!\n\nTest succeeded!", result.getResponse());
	}

	@Test
	void callWithASinglePromptHasOneResponse() {
		assertEquals(List.of("Test succeeded!"), smokeTest.call(ENV, List.of("only"), ARGS).getResponses());
	}

	@Test
	void callWithNoPromptsHasNoResponses() {
		assertEquals(List.of(), smokeTest.call(ENV, List.of(), ARGS).getResponses());
	}

	@Test
	void getContextIsAlwaysEmpty() {
		assertEquals(List.of(), smokeTest.getContext(ENV, List.of("prompt"), ARGS));
		assertEquals(List.of(), smokeTest.getContext(ENV, List.of(), ARGS));
	}

	@Test
	void contextHashCodeCombinesTheArgumentHashAndThePrompts() {
		final List<String> prompts = List.of("a", "b");

		assertEquals(31 * CONFIG_HASH + prompts.hashCode(), smokeTest.contextHashCode(ENV, prompts, ARGS));
	}

	@Test
	void contextHashCodeChangesWithThePrompts() {
		assertNotEquals(
				smokeTest.contextHashCode(ENV, List.of("a"), ARGS),
				smokeTest.contextHashCode(ENV, List.of("b"), ARGS));
	}

	@Test
	void localArgumentsDelegateToTheToStringGenerator() {
		final SmokeTestConfig.LocalArguments arguments = config.new LocalArguments(ARGS, List.of("prompt"), ENV);

		assertEquals("config", arguments.toString());
		assertEquals(CONFIG_HASH, arguments.hashCode());
	}
}
