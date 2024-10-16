package secondbrain.domain.validate.impl;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.exceptions.EmptyList;
import secondbrain.domain.validate.ValidateList;

import java.util.List;

@ApplicationScoped
public class ValidateListImpl implements ValidateList {

    @Override
    public <T> List<T> throwIfEmpty(final List<T> value) {
        if (value == null || value.isEmpty()) {
            throw new EmptyList("List is empty");
        }
        return value;
    }
}
