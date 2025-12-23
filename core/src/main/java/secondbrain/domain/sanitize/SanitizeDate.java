package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Sanitize a date passed in as an argument.
 */
@ApplicationScoped
@Identifier("sanitizeDate")
public class SanitizeDate implements SanitizeArgument {
    @Override
    public String sanitize(@Nullable final String argument, final String document) {
        if (StringUtils.isBlank(argument)) {
            return "";
        }

        final String trimmedDate = argument.trim();

        // A bunch of hallucinations that we need to ignore
        final String[] invalid = {
                "now",
        };

        if (Arrays.stream(invalid).anyMatch(trimmedDate::equalsIgnoreCase)) {
            return "";
        }

        return argument;
    }
}
