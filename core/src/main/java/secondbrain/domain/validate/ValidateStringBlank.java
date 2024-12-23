package secondbrain.domain.validate;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.EmptyString;

import java.util.function.Function;


@ApplicationScoped
public class ValidateStringBlank implements ValidateString {
    @Override
    public String throwIfEmpty(@Nullable final String value) {
        if (StringUtils.isBlank(value)) {
            throw new EmptyString("String is empty");
        }
        return value;
    }

    @Override
    public <T> T throwIfEmpty(final T source, final Function<T, String> getContext) {
        if (StringUtils.isBlank(getContext.apply(source))) {
            throw new EmptyString("String is empty");
        }
        return source;
    }
}
