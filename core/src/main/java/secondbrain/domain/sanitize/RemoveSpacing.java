package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Remove extra spacing from a document.
 */
@ApplicationScoped
@Identifier("removeSpacing")
public class RemoveSpacing implements SanitizeDocument {
    @Override
    @Nullable
    public String sanitize(@Nullable final String document) {
        if (StringUtils.isEmpty(document)) {
            return document;
        }

        return String.join(
                "\n\n",
                Arrays.stream(document.split("\n\n"))
                        .filter(s -> !s.isBlank())
                        .toList()).trim();

    }
}
