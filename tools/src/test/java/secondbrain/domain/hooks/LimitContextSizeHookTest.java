package secondbrain.domain.hooks;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.limit.ListLimiterAtomicCutOff;
import secondbrain.domain.test.TestConfigUtil;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LimitContextSizeHook}. The config is registered at the start of each test, before the
 * application scoped hook is first used and its config is injected.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(LimitContextSizeHook.class)
@AddBeanClasses(ListLimiterAtomicCutOff.class)
class LimitContextSizeHookTest {
	@Inject
	private LimitContextSizeHook hook;

	private static RagDocumentContext<String> context(final String document) {
		return new RagDocumentContext<>("tool", "label", document, List.of(), "id-" + document, "source", "https://example.org/" + document);
	}

	private static void maxSize(final int size) {
		TestConfigUtil.registerConfig(Map.of("sb.limitcontextsize.chars", String.valueOf(size)));
	}

	@Test
	void theNameIsTheClassName() {
		TestConfigUtil.registerConfig(Map.of());

		assertEquals("LimitContextSizeHook", hook.getName());
	}

	@Test
	@SuppressWarnings("NullAway")
	void aNullListIsEmpty() {
		maxSize(5);
		final @Nullable List<RagDocumentContext<String>> contexts = null;

		assertEquals(List.of(), hook.process("tool", contexts));
	}

	@Test
	void withoutAConfiguredSizeTheContextsAreUnchanged() {
		TestConfigUtil.registerConfig(Map.of());
		final List<RagDocumentContext<String>> contexts = List.of(context("a long document"));

		assertSame(contexts, hook.process("tool", contexts));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1, -100})
	void aSizeOfZeroOrLessLeavesTheContextsUnchanged(final int size) {
		maxSize(size);
		final List<RagDocumentContext<String>> contexts = List.of(context("a long document"));

		assertSame(contexts, hook.process("tool", contexts));
	}

	@Test
	void documentsAreTrimmedToTheMaximumSize() {
		maxSize(5);

		final List<RagDocumentContext<String>> result = hook.process("tool", List.of(
				context("abcdefgh"),
				context("abcdef"),
				context("abcde"),
				context("abcd"),
				context("")));

		assertEquals(List.of("abcde", "abcde", "abcde", "abcd", ""), result.stream().map(RagDocumentContext::document).toList());
	}

	@Test
	void aSizeOfOneKeepsTheFirstCharacter() {
		maxSize(1);

		assertEquals("a", hook.process("tool", List.of(context("abc"))).getFirst().document());
	}

	@Test
	void trimmingKeepsTheOtherContextFields() {
		maxSize(3);
		final RagDocumentContext<String> original = context("abcdef");

		final RagDocumentContext<String> trimmed = hook.process("tool", List.of(original)).getFirst();

		assertEquals(original.updateDocument("abc"), trimmed);
		assertEquals("tool", trimmed.tool());
		assertEquals("label", trimmed.contextLabel());
		assertEquals("id-abcdef", trimmed.id());
		assertEquals("source", trimmed.source());
		assertEquals("https://example.org/abcdef", trimmed.link());
	}
}
