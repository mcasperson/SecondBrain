package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;
import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remove a markdown block from a document.
 */
@ApplicationScoped
@Identifier("findFirstMarkdownBlock")
public class GetFirstMarkdownBlock implements SanitizeDocument {
    private static final Pattern MARKDOWN_BLOCK_START_REGEX = Pattern.compile("```(.*?)\n(.*?)\n```", Pattern.DOTALL);

    @Override
    @Nullable
    public String sanitize(@Nullable final String document) {
        if (StringUtils.isEmpty(document)) {
            return document;
        }

        final String trimmedDocument = document.trim();
        final Matcher matcher = MARKDOWN_BLOCK_START_REGEX.matcher(trimmedDocument);

        if (matcher.find()) {
            return matcher.group(2);
        }

        return document;
    }
}
