package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Sanitize an email address passed in as an argument.
 */
@ApplicationScoped
@Identifier("sanitizeEmail")
public class SanitizeEmail implements SanitizeArgument {
    @Override
    public String sanitize(@Nullable final String argument, @Nullable final String document) {
        if (StringUtils.isBlank(argument)) {
            return "";
        }

        // find the first thing that looks like an email address
        final String email = Arrays.stream(argument.split(" "))
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .filter(e -> EmailValidator.getInstance().isValid(e))
                .limit(1)
                .findFirst()
                .orElse("");

        // A bunch of hallucinations that we need to ignore
        final String[] invalid = {
                "admin@example.com",
                "admin@example.org",
        };

        if (Arrays.stream(invalid).anyMatch(email::equalsIgnoreCase)) {
            return "";
        }

        return email;
    }
}
