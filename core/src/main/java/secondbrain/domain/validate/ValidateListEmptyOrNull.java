package secondbrain.domain.validate;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.EmptyList;
import secondbrain.domain.exceptions.InsufficientContext;

import java.util.List;
import java.util.function.Function;

@ApplicationScoped
public class ValidateListEmptyOrNull implements ValidateList {

    @Override
    public <T> List<T> throwIfEmpty(@Nullable final List<T> value) {
        if (value == null || value.isEmpty()) {
            throw new EmptyList("List is empty");
        }
        return value;
    }

    @Override
    public <T> List<T> throwIfEmpty(List<T> value, Function<T, String> getContext) {
        if (value.stream().noneMatch(item -> StringUtils.isNotBlank(getContext.apply(item)))) {
            throw new EmptyList("List has empty or blank items");
        }
        return value;
    }

    @Override
    public <T> List<T> throwIfInsufficient(@Nullable final List<T> value, final int size) {
        if (value == null || value.size() < size) {
            throw new InsufficientContext("The context is insufficient");
        }
        return value;
    }
}
