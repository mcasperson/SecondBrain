package secondbrain.domain.validate;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.EmptyList;

import java.util.List;

@ApplicationScoped
public class ValidateListEmptyOrNull implements ValidateList {

    @Override
    public <T> List<T> throwIfEmpty(@Nullable final List<T> value) {
        if (value == null || value.isEmpty()) {
            throw new EmptyList("List is empty");
        }
        return value;
    }
}
