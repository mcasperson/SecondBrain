package secondbrain.domain.list;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class TrimmedCommaSeparatedStringToList implements StringToList {
    @Override
    public List<String> convert(final String input) {
        return Stream.of(Objects.requireNonNullElse(input, "").split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }
}
