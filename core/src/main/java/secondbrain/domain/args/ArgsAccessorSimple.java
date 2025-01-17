package secondbrain.domain.args;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.validate.ValidateString;

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
    public String getArgument(
            final List<ToolArgs> arguments,
            final String argName,
            final String defaultValue) {
        return arguments.stream()
                .filter(arg -> arg.argName().equals(argName))
                .findFirst().map(ToolArgs::argValue)
                .orElse(defaultValue);
    }

    @Override
    public String getArgument(final ArgsAccessorSystemProperty systemProperty,
                              final List<ToolArgs> arguments,
                              final Map<String, String> context,
                              final String argName,
                              final String contextName,
                              final String defaultValue) {
        // start with system properties
        return Try.of(systemProperty::getValue)
                .mapTry(validateString::throwIfEmpty)
                // then try the context
                .recover(e -> context.get(contextName))
                .mapTry(validateString::throwIfEmpty)
                // then get the user supplied argument
                .recover(e -> getArgument(arguments, argName, defaultValue))
                .mapTry(validateString::throwIfEmpty)
                // fallback to the default
                .recover(e -> defaultValue)
                .get();
    }

    @Override
    public String getArgument(List<ToolArgs> arguments, List<SanitizeArgument> sanitizers, String prompt, String argName, String defaultValue) {
        final String arg = getArgument(arguments, argName, defaultValue);
        final String sanitized = sanitizers.stream()
                .reduce(arg, (s, sanitizer) -> sanitizer.sanitize(s, prompt), (s1, s2) -> s2);

        if (StringUtils.isBlank(sanitized)) {
            return defaultValue;
        }

        return sanitized;
    }
}
