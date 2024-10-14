package secondbrain.domain.strings.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.strings.ValidateString;


@ApplicationScoped
public class ValidateStringImpl implements ValidateString {
    @Override
    public @NotNull String throwIfEmpty(String value) {
        if (StringUtils.isEmpty(value)) {
            throw new EmptyString("String is empty");
        }
        return value;
    }
}
