package secondbrain.domain.validate;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.EmptyString;


@ApplicationScoped
public class ValidateStringBlank implements ValidateString {
    @Override
    public String throwIfEmpty(@Nullable String value) {
        if (StringUtils.isBlank(value)) {
            throw new EmptyString("String is empty");
        }
        return value;
    }
}
