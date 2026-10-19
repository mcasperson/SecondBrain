package secondbrain.domain.args;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.validate.ValidateStringBlank;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
// Null arguments are passed deliberately to cover the null handling
@SuppressWarnings("NullAway")
class ArgsAccessorSimpleTest {
    private static final String NAME = "keywords";
    private static final String CONTEXT_NAME = "context_keywords";

    @Inject
    private ArgsAccessorSimple accessor;

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    /**
     * Configs pass an Optional's get method as the system property, so an empty Optional throws.
     */
    private static ArgsAccessorSystemProperty config(final Optional<String> value) {
        return value::get;
    }

    private static List<ToolArgs> args(final String value, final boolean trusted) {
        return List.of(new ToolArgs(NAME, value, trusted));
    }

    private Argument get(final ArgsAccessorSystemProperty systemProperty, final List<ToolArgs> arguments, final Map<String, String> context) {
        return accessor.getArgument(systemProperty, arguments, context, NAME, CONTEXT_NAME, "default");
    }

    // getArgument(arguments, argName, defaultValue)

    @Test
    void simpleGetArgumentReturnsTheFirstMatchingArgumentWithItsTrustFlag() {
        final List<ToolArgs> arguments = List.of(
                new ToolArgs("other", "ignored", true),
                new ToolArgs(NAME, "first", false),
                new ToolArgs(NAME, "second", true));

        assertEquals(new Argument("first", false), accessor.getArgument(arguments, NAME, "default"));
        assertEquals(new Argument("ignored", true), accessor.getArgument(arguments, "other", "default"));
    }

    @Test
    void simpleGetArgumentFallsBackToATrustedDefault() {
        assertEquals(new Argument("default", true), accessor.getArgument(args("value", false), "missing", "default"));
        assertEquals(new Argument("default", true), accessor.getArgument(null, NAME, "default"));
        assertEquals(new Argument("default", true), accessor.getArgument(args("value", true), null, "default"));
    }

    @Test
    void simpleGetArgumentReturnsBlankArgumentsAsIs() {
        // Unlike the precedence overload, this overload does not replace blank values with the default
        assertEquals(new Argument(" ", false), accessor.getArgument(args(" ", false), NAME, "default"));
    }

    // getArgument(systemProperty, arguments, context, argName, contextName, defaultValue)

    @Test
    void nonBlankConfigWinsOverContextAndArgument() {
        final Argument result = get(config(Optional.of("from-config")), args("from-argument", false), Map.of(CONTEXT_NAME, "from-context"));

        assertEquals(new Argument("from-config", true), result);
    }

    @Test
    void nonBlankConfigWinsOverADifferentDefault() {
        // This is why a config defaultValue overrides per-request context and argument values
        assertEquals(new Argument("config-default", true),
                accessor.getArgument(config(Optional.of("config-default")), args("from-argument", true), Map.of(CONTEXT_NAME, "from-context"), NAME, CONTEXT_NAME, "accessor-default"));
    }

    @Test
    void emptyConfigFallsThroughToTheContext() {
        assertEquals(new Argument("from-context", true), get(config(Optional.empty()), args("from-argument", false), Map.of(CONTEXT_NAME, "from-context")));
    }

    @Test
    void blankConfigFallsThroughToTheContext() {
        assertEquals(new Argument("from-context", true), get(config(Optional.of("  ")), args("from-argument", false), Map.of(CONTEXT_NAME, "from-context")));
    }

    @Test
    void failingConfigFallsThroughToTheContext() {
        final ArgsAccessorSystemProperty failing = () -> {
            throw new IllegalStateException("config unavailable");
        };

        assertEquals(new Argument("from-context", true), get(failing, args("from-argument", false), Map.of(CONTEXT_NAME, "from-context")));
    }

    @Test
    void nullSystemPropertyIsTreatedAsMissing() {
        assertEquals(new Argument("from-context", true), get(null, args("from-argument", false), Map.of(CONTEXT_NAME, "from-context")));
        assertEquals(new Argument("from-argument", false), get(null, args("from-argument", false), null));
    }

    @Test
    void contextValuesAreAlwaysTrusted() {
        // An untrusted argument does not make the context value untrusted
        assertEquals(new Argument("from-context", true), get(config(Optional.empty()), args("from-argument", false), Map.of(CONTEXT_NAME, "from-context")));
    }

    @Test
    void contextIsReadByContextNameNotArgumentName() {
        final Argument result = get(config(Optional.empty()), args("from-argument", true), Map.of(NAME, "wrong-key"));

        assertEquals(new Argument("from-argument", true), result);
    }

    @Test
    void blankOrMissingContextValuesFallThroughToTheArgument() {
        final Map<String, String> blankContext = new HashMap<>();
        blankContext.put(CONTEXT_NAME, " ");

        assertEquals(new Argument("from-argument", false), get(config(Optional.empty()), args("from-argument", false), blankContext));
        assertEquals(new Argument("from-argument", false), get(config(Optional.empty()), args("from-argument", false), Map.of()));
        assertEquals(new Argument("from-argument", false), get(config(Optional.empty()), args("from-argument", false), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void blankContextNameSkipsTheContext(final String contextName) {
        final Map<String, String> context = new HashMap<>();
        context.put(contextName, "from-context");

        assertEquals(new Argument("from-argument", true),
                accessor.getArgument(config(Optional.empty()), args("from-argument", true), context, NAME, contextName, "default"));
    }

    @Test
    void nullContextNameSkipsTheContext() {
        assertEquals(new Argument("from-argument", true),
                accessor.getArgument(config(Optional.empty()), args("from-argument", true), Map.of("", "from-context"), NAME, null, "default"));
    }

    @Test
    void argumentKeepsItsTrustFlag() {
        assertEquals(new Argument("from-argument", false), get(config(Optional.empty()), args("from-argument", false), Map.of()));
        assertEquals(new Argument("from-argument", true), get(config(Optional.empty()), args("from-argument", true), Map.of()));
    }

    @Test
    void blankArgumentFallsBackToATrustedDefault() {
        assertEquals(new Argument("default", true), get(config(Optional.empty()), args("  ", false), Map.of()));
    }

    @Test
    void missingEverythingReturnsATrustedDefault() {
        assertEquals(new Argument("default", true), get(config(Optional.empty()), List.of(), Map.of()));
        assertEquals(new Argument("default", true), accessor.getArgument(null, null, null, null, null, "default"));
    }

    @Test
    void blankDefaultIsReturnedWhenNothingElseIsSet() {
        assertEquals(new Argument("", true), accessor.getArgument(config(Optional.empty()), List.of(), Map.of(), NAME, CONTEXT_NAME, ""));
    }

    // getArgumentList

    @Test
    void argumentListSplitsTrimsAndDropsBlankEntries() {
        final List<Argument> result = accessor.getArgumentList(config(Optional.empty()), args(" alpha, beta gamma ,, ,delta ", false), Map.of(), NAME, CONTEXT_NAME, "");

        assertEquals(List.of(new Argument("alpha", false), new Argument("beta gamma", false), new Argument("delta", false)), result);
    }

    @Test
    void argumentListUsesTheSamePrecedenceAndTrust() {
        assertEquals(List.of(new Argument("c1", true), new Argument("c2", true)),
                accessor.getArgumentList(config(Optional.of("c1,c2")), args("a1", false), Map.of(CONTEXT_NAME, "x1"), NAME, CONTEXT_NAME, ""));
        assertEquals(List.of(new Argument("x1", true)),
                accessor.getArgumentList(config(Optional.empty()), args("a1", false), Map.of(CONTEXT_NAME, "x1"), NAME, CONTEXT_NAME, ""));
        assertEquals(List.of(new Argument("d1", true), new Argument("d2", true)),
                accessor.getArgumentList(config(Optional.empty()), List.of(), Map.of(), NAME, CONTEXT_NAME, "d1, d2"));
    }

    @Test
    void argumentListIsEmptyForABlankDefault() {
        assertEquals(List.of(), accessor.getArgumentList(null, null, null, NAME, CONTEXT_NAME, ""));
    }

    // getArgument(arguments, sanitizers, prompt, argName, defaultValue)

    @Test
    void sanitizersAreAppliedInOrderWithThePrompt() {
        final List<String> prompts = new ArrayList<>();
        final SanitizeArgument first = (value, prompt) -> {
            prompts.add(prompt);
            return value + "-first";
        };
        final SanitizeArgument second = (value, prompt) -> {
            prompts.add(prompt);
            return value + "-second";
        };

        final Argument result = accessor.getArgument(args("raw", false), List.of(first, second), "the prompt", NAME, "default");

        // The trust flag of the argument is kept through sanitization
        assertEquals(new Argument("raw-first-second", false), result);
        assertEquals(List.of("the prompt", "the prompt"), prompts);
    }

    @Test
    void sanitizersReceiveTheDefaultWhenTheArgumentIsMissing() {
        final SanitizeArgument upper = (value, prompt) -> String.valueOf(value).toUpperCase(Locale.ROOT);

        assertEquals(new Argument("DEFAULT", true), accessor.getArgument(List.of(), List.of(upper), "prompt", NAME, "default"));
    }

    @Test
    void sanitizedBlankValueFallsBackToATrustedDefault() {
        final SanitizeArgument blank = (value, prompt) -> " ";

        assertEquals(new Argument("default", true), accessor.getArgument(args("raw", false), List.of(blank), "prompt", NAME, "default"));
    }

    @Test
    void noSanitizersReturnsTheArgument() {
        assertEquals(new Argument("raw", false), accessor.getArgument(args("raw", false), null, null, NAME, "default"));
        assertEquals(new Argument("default", true), accessor.getArgument(args(" ", false), List.of(), null, NAME, "default"));
    }
}
