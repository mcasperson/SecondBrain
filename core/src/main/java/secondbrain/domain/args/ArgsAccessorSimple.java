package secondbrain.domain.args;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.validate.ValidateString;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A service for extracting exact argument matches from a list of arguments.
 */
@ApplicationScoped
public class ArgsAccessorSimple implements ArgsAccessor {

    @Inject
    private ValidateString validateString;

    @Override
    public Argument getArgument(
            final List<ToolArgs> arguments,
            final String argName,
            final String defaultValue) {
        return arguments.stream()
                .filter(arg -> arg.argName().equals(argName))
                .findFirst()
                .map(ToolArgs::toArgument)
                .orElse(new Argument(defaultValue, true));
    }

    @Override
    public Argument getArgument(final ArgsAccessorSystemProperty systemProperty,
                                final List<ToolArgs> arguments,
                                final Map<String, String> context,
                                final String argName,
                                final String contextName,
                                final String defaultValue) {
        // start with system properties
        return Try.of(() -> new Argument(systemProperty.getValue(), true))
                .mapTry(v -> validateString.throwIfEmpty(v, Argument::value))
                // then try the context
                .recover(e -> new Argument(context.get(contextName), true))
                .mapTry(v -> validateString.throwIfEmpty(v, Argument::value))
                // then get the user supplied argument
                .recover(e -> getArgument(arguments, argName, defaultValue))
                .mapTry(v -> validateString.throwIfEmpty(v, Argument::value))
                // fallback to the default
                .recover(e -> new Argument(defaultValue, true))
                .get();
    }

    @Override
    public List<Argument> getArgumentList(
            final ArgsAccessorSystemProperty systemProperty,
            final List<ToolArgs> arguments,
            final Map<String, String> context,
            final String argName,
            final String contextName,
            final String defaultValue) {
        final Argument argument = getArgument(systemProperty, arguments, context, argName, contextName, defaultValue);
        return Arrays.stream(argument.value().split(","))
                .map(String::trim)
                .map(v -> new Argument(v, argument.trusted()))
                .toList();
    }

    @Override
    public Argument getArgument(List<ToolArgs> arguments, List<SanitizeArgument> sanitizers, String prompt, String argName, String defaultValue) {
        final Argument arg = getArgument(arguments, argName, defaultValue);
        final Argument sanitized = sanitizers.stream()
                .reduce(arg, (s, sanitizer) -> s.replaceValue(sanitizer.sanitize(s.value(), prompt)), (s1, s2) -> s2);

        if (StringUtils.isBlank(sanitized.value())) {
            return new Argument(defaultValue, true);
        }

        return sanitized;
    }
}
