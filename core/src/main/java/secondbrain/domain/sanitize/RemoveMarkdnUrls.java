package secondbrain.domain.sanitize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.apache.commons.lang.StringUtils;

import java.util.Arrays;

@ApplicationScoped
@Named
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
