package secondbrain.domain.validate;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.EmptyString;

import java.util.function.Function;


@ApplicationScoped
public class ValidateStringBlank implements ValidateString {
    @Override
    public String throwIfBlank(@Nullable final String value) {
        if (StringUtils.isBlank(value)) {
            throw new EmptyString("String validation failed - string is empty");
        }
        return value;
    }

    @Override
    public <T> T throwIfBlank(final T source, final Function<T, String> getContext) {
        if (StringUtils.isBlank(getContext.apply(source))) {
            throw new EmptyString("String validation failed - string is empty");
        }
        return source;
    }

    @Override
    public boolean isBlank(String value) {
        return StringUtils.isBlank(value);
    }

    @Override
    public boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    @Override
    public <T> boolean isBlank(T source, Function<T, String> getContext) {
        return StringUtils.isBlank(getContext.apply(source));
    }

    @Override
    public <T> boolean isNotBlank(T source, Function<T, String> getContext) {
        return !isBlank(source, getContext);
    }
}
