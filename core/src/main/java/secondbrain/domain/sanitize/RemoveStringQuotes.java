package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
@Identifier("removeStringQuotes")
public class RemoveStringQuotes implements SanitizeDocument {
    private static final Pattern QUOTE_PATTERN = Pattern.compile("^[\"'](.*)[\"']$");

    @Override
    public String sanitize(final String document) {
        if (document == null) {
            return null;
        }

        final Matcher match = QUOTE_PATTERN.matcher(document);

        if (match.matches()) {
            return match.group(1);
        }

        return document;
    }
}
