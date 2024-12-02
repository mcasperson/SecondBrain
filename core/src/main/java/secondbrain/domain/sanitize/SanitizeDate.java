package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

/**
 * Sanitize a date passed in as an argument.
 */
@ApplicationScoped
@Identifier("sanitizeDate")
public class SanitizeDate implements SanitizeDocument {
    @Override
    public String sanitize(final String document) {
        if (StringUtils.isBlank(document)) {
            return "";
        }

        final String trimmedDate = document.trim();

        // A bunch of hallucinations that we need to ignore
        final String[] invalid = {
                "now",
        };

        if (Arrays.stream(invalid).anyMatch(trimmedDate::equalsIgnoreCase)) {
            return "";
        }

        return document;
    }
}
