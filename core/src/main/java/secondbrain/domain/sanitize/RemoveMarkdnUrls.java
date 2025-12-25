package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Remove anything that looks like a MarkDn URL.
 */
@ApplicationScoped
@Identifier("removeMarkdnUrls")
public class RemoveMarkdnUrls implements SanitizeDocument {
    @Override
    @Nullable
    public String sanitize(@Nullable final String document) {
        if (StringUtils.isEmpty(document)) {
            return document;
        }

        return document
                .replaceAll("\\|", " ")
                .replaceAll(">", " ")
                .replaceAll("<", " ");

    }
}
