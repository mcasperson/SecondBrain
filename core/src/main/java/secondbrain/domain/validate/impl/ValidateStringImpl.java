package secondbrain.domain.validate.impl;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.validate.ValidateString;


@ApplicationScoped
public class ValidateStringImpl implements ValidateString {
    @Override
    public String throwIfEmpty(@Nullable String value) {
        if (StringUtils.isEmpty(value)) {
            throw new EmptyString("String is empty");
        }
        return value;
    }
}
