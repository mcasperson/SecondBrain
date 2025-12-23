package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phi-4 would continually return an explaination when asked for a single number. So we use this santizier
 * to return just the first digits found in the response.
 */
@ApplicationScoped
@Identifier("getFirstDigits")
public class GetFirstDigits implements SanitizeDocument {
    private static final Pattern DIGIT_PATTERN = Pattern.compile("^(\\d+)");

    @Override
    @Nullable
    public String sanitize(final String document) {
        if (document == null) {
            return null;
        }

        final Matcher matcher = DIGIT_PATTERN.matcher(document);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return document;
    }
}
