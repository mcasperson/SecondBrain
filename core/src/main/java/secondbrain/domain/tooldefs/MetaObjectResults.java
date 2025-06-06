package secondbrain.domain.tooldefs;

import org.apache.commons.lang.math.NumberUtils;
import org.apache.tika.utils.StringUtils;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

public class MetaObjectResults extends ArrayList<MetaObjectResult> {
    public MetaObjectResults() {
        super();
    }

    public MetaObjectResults(final Iterable<MetaObjectResult> results) {
        super();
        if (results != null) {
            results.forEach(this::add);
        }
    }

    public Optional<MetaObjectResult> getByName(final String name) {
        if (StringUtils.isBlank(name)) {
            return Optional.empty();
        }

        return stream()
                .filter(result -> Objects.equals(result.name(), name))
                .findFirst();
    }

    public Optional<Integer> getIntValueByName(final String name) {
        if (StringUtils.isBlank(name)) {
            return Optional.empty();
        }

        return getByName(name)
                .map(MetaObjectResult::value)
                .map(v -> NumberUtils.toInt(v.toString()));
    }

    public int getIntValueByName(final String name, final int defaultValue) {
        if (StringUtils.isBlank(name)) {
            return defaultValue;
        }

        return getByName(name)
                .map(MetaObjectResult::value)
                .map(v -> NumberUtils.toInt(v.toString()))
                .orElse(defaultValue);
    }

    public Optional<String> getStringValueByName(final String name) {
        if (StringUtils.isBlank(name)) {
            return Optional.empty();
        }

        return getByName(name)
                .map(MetaObjectResult::value)
                .map(Object::toString);
    }
}
