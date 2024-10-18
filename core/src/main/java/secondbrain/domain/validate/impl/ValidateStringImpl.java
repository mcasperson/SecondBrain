package secondbrain.domain.validate.impl;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.validate.ValidateString;


@ApplicationScoped
public class ValidateStringImpl implements ValidateString {
    @Override
    public String throwIfEmpty(String value) {
        if (StringUtils.isEmpty(value)) {
            throw new EmptyString("String is empty");
        }
        return value;
    }
}
