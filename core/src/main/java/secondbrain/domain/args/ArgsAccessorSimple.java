package secondbrain.domain.args;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.jooq.lambda.Seq;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.validate.ValidateString;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A service for extracting exact argument matches from a list of arguments.
 */
@ApplicationScoped
public class ArgsAccessorSimple implements ArgsAccessor {

    @Inject
    private ValidateString validateString;

    @Override
    public Argument getArgument(
            @Nullable final List<ToolArgs> arguments,
            @Nullable final String argName,
            final String defaultValue) {
        return Objects.requireNonNullElse(arguments, List.<ToolArgs>of()).stream()
                .filter(arg -> arg.argName().equals(argName))
                .findFirst()
                .map(ToolArgs::toArgument)
                .orElse(new Argument(defaultValue, true));
    }

    @Override
    public Argument getArgument(@Nullable final ArgsAccessorSystemProperty systemProperty,
                                @Nullable final List<ToolArgs> arguments,
                                @Nullable final Map<String, String> context,
                                @Nullable final String argName,
                                @Nullable final String contextName,
                                final String defaultValue) {
        // start with system properties
        return Try.of(() -> new Argument(systemProperty.getValue(), true))
                .mapTry(v -> validateString.throwIfBlank(v, Argument::value))
                // then try the context
                .recover(e -> validateString.isNotBlank(contextName) ? new Argument(context.get(contextName), true) : null)
                .mapTry(v -> validateString.throwIfBlank(v, Argument::value))
                // then get the user supplied argument
                .recover(e -> getArgument(arguments, argName, defaultValue))
                .mapTry(v -> validateString.throwIfBlank(v, Argument::value))
                // fallback to the default
                .recover(e -> new Argument(defaultValue, true))
                .get();
    }

    @Override
    public List<Argument> getArgumentList(
            @Nullable final ArgsAccessorSystemProperty systemProperty,
            @Nullable final List<ToolArgs> arguments,
            @Nullable final Map<String, String> context,
            @Nullable final String argName,
            @Nullable final String contextName,
            @Nullable final String defaultValue) {
        final Argument argument = getArgument(systemProperty, arguments, context, argName, contextName, defaultValue);
        final String fixedValue = Objects.requireNonNullElse(argument.value(), "");
        return Arrays.stream(fixedValue.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(v -> new Argument(v, argument.trusted()))
                .toList();
    }

    @Override
    public Argument getArgument(
            @Nullable final List<ToolArgs> arguments,
            @Nullable final List<SanitizeArgument> sanitizers,
            @Nullable final String prompt,
            @Nullable final String argName,
            @Nullable final String defaultValue) {
        final Argument arg = getArgument(arguments, argName, defaultValue);

        final Argument sanitized = Seq.seq(Objects.requireNonNullElse(sanitizers, List.<SanitizeArgument>of()).stream())
                .foldLeft(arg, (s, sanitizer) -> s.replaceValue(sanitizer.sanitize(s.value(), prompt)));

        if (StringUtils.isBlank(sanitized.value())) {
            return new Argument(defaultValue, true);
        }

        return sanitized;
    }
}
