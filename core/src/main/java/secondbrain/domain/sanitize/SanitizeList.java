package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;
import java.util.List;

import java.util.Arrays;

@ApplicationScoped
@Identifier("sanitizeList")
public class SanitizeList implements SanitizeArgument {
    @Override
    public String sanitize(final String argument, final String document) {
        final List<String> items = Arrays.stream(argument.split(","))
                .map(String::trim)
                .filter(s -> !StringUtils.isBlank(s))
                .filter(arg -> document.toLowerCase().contains(arg.toLowerCase()))
                .toList();

        return String.join(",", items);
    }
}
