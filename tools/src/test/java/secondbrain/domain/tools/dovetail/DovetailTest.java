package secondbrain.domain.tools.dovetail;

import org.junit.jupiter.api.Test;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Dovetail} using reflection to validate public API
 * without requiring full Weld CDI container setup.
 */
@SuppressWarnings("NullAway")
class DovetailTest {

    private final Dovetail dovetail = new Dovetail();

    @Test
    void testNameReturnsCorrectValue() {
        assertEquals("Dovetail", dovetail.getName());
    }

    @Test
    void testGetDescriptionReturnsNonEmptyString() {
        final String description = dovetail.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("user research"));
    }

    @Test
    void testGetNameIsNotBlank() {
        final String name = dovetail.getName();
        assertNotNull(name);
        assertFalse(name.isBlank());
    }

    @Test
    void testGetArgumentsIsNonEmpty() {
        final List<ToolArguments> arguments = dovetail.getArguments();
        assertNotNull(arguments);
        assertFalse(arguments.isEmpty());
    }

    @Test
    void testGetContextLabelReturnsExpectedLabel() {
        assertEquals("Dovetail data item", dovetail.getContextLabel());
    }

    @Test
    void testArgumentsContainAllExpectedKeys() {
        final List<ToolArguments> arguments = dovetail.getArguments();
        final List<String> argNames = arguments.stream()
                .map(ToolArguments::name)
                .collect(Collectors.toList());

        assertTrue(argNames.contains(Dovetail.DOVETAIL_API_KEY_ARG),
                "Should contain apiKey argument");
        assertTrue(argNames.contains(Dovetail.DOVETAIL_BASE_URL_ARG),
                "Should contain dovetailBaseUrl argument");

        // Verify no duplicate names
        assertEquals(argNames.size(),
                argNames.stream().distinct().count(),
                "Argument names should be unique");
    }

    @Test
    void testArgumentsHasCorrectCount() {
        final List<ToolArguments> arguments = dovetail.getArguments();
        // API key, base URL, days, start/end date, keywords, keyword window, auto-generate keywords,
        // summarize document, summarize prompt, context filter question/rating/default/filters,
        // preinit/pre/post hooks, TTL seconds, minimum content length
        assertEquals(19, arguments.size(), "Should have 19 expected arguments");
    }

    @Test
    void testGetArgumentsAreNonNull() {
        final List<ToolArguments> arguments = dovetail.getArguments();
        for (final ToolArguments arg : arguments) {
            assertNotNull(arg.name(), "Argument name should not be null");
            assertNotNull(arg.description(), "Argument description should not be null");
            assertNotNull(arg.defaultValue(), "Argument default value should not be null");
        }
    }

    @Test
    void testDefaultApiKeyArgHasDefaultValue() {
        final List<ToolArguments> arguments = dovetail.getArguments();
        final ToolArguments apiKeyArg = arguments.stream()
                .filter(a -> a.name().equals(Dovetail.DOVETAIL_API_KEY_ARG))
                .findFirst()
                .orElseThrow(() -> new AssertionError("apiKey argument not found"));

        assertEquals("", apiKeyArg.defaultValue(), "apiKey default should be empty");
    }

    @Test
    void testDefaultBaseURLArgHasExpectedDefaultValue() {
        final List<ToolArguments> arguments = dovetail.getArguments();
        final ToolArguments baseUrlArg = arguments.stream()
                .filter(a -> a.name().equals(Dovetail.DOVETAIL_BASE_URL_ARG))
                .findFirst()
                .orElseThrow(() -> new AssertionError("dovetailBaseUrl argument not found"));

        assertEquals("https://dovetail.com", baseUrlArg.defaultValue(),
                "dovetailBaseUrl default should be https://dovetail.com");
    }

    @Test
    void testTtlSecondsArgumentHasDefault() {
        final List<ToolArguments> arguments = dovetail.getArguments();
        final ToolArguments ttlArg = arguments.stream()
                .filter(a -> a.name().equals(Dovetail.TTL_SECONDS_ARG))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ttlSeconds argument not found"));

        assertEquals("86400", ttlArg.defaultValue(), "TTL should default to 86400 (24 hours)");
    }

    @Test
    void testMinimumContentLengthArgumentHasDefault() {
        final List<ToolArguments> arguments = dovetail.getArguments();
        final ToolArguments minContentArg = arguments.stream()
                .filter(a -> a.name().equals(Dovetail.MINIMUM_CONTENT_LENGTH_ARG))
                .findFirst()
                .orElseThrow(() -> new AssertionError("minimumContentLength argument not found"));

        assertEquals("0", minContentArg.defaultValue(),
                "Minimum content length should default to 0 (no minimum)");
    }
}
