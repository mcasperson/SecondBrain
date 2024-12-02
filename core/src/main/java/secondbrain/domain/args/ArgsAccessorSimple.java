package secondbrain.domain.args;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;

/**
 * A service for extracting exact argument matches from a list of arguments.
 */
@ApplicationScoped
public class ArgsAccessorSimple implements ArgsAccessor {

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
    public String getArgument(List<ToolArgs> arguments, List<SanitizeDocument> sanitizers, String argName, String defaultValue) {
        final String arg = getArgument(arguments, argName, defaultValue);
        final String sanitized = sanitizers.stream()
                .reduce(arg, (s, sanitizer) -> sanitizer.sanitize(s), (s1, s2) -> s2);

        if (StringUtils.isBlank(sanitized)) {
            return defaultValue;
        }

        return sanitized;
    }
}
