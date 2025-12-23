package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;
import org.jspecify.annotations.Nullable;

import java.util.List;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

@ApplicationScoped
@Identifier("sanitizeList")
public class SanitizeList implements SanitizeArgument {
    @Override
    public String sanitize(@Nullable final String argument, @Nullable final String document) {
        final String fixedArgument = Objects.requireNonNullElse(argument, "");
        final String fixedDocument = Objects.requireNonNullElse(document, "");

        final List<String> items = Arrays.stream(fixedArgument.split(","))
                .map(String::trim)
                .filter(s -> !StringUtils.isBlank(s))
                .filter(arg -> fixedDocument.toLowerCase(Locale.ROOT).contains(arg.toLowerCase(Locale.ROOT)))
                .toList();

        return String.join(",", items);
    }
}
