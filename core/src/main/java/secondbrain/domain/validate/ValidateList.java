package secondbrain.domain.validate;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public interface ValidateList {
    @NotNull
    <T> List<T> throwIfEmpty(List<T> value);
}
