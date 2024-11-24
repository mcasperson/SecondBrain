package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;

/**
 * Remove anything that looks like a MarkDn URL.
 */
@ApplicationScoped
@Identifier("removeMarkdnUrls")
public class RemoveMarkdnUrls implements SanitizeDocument {
    @Override
    public String sanitize(final String document) {
        if (StringUtils.isEmpty(document)) {
            return document;
        }

        return document
                .replaceAll("\\|", " ")
                .replaceAll(">", " ")
                .replaceAll("<", " ");

    }
}
