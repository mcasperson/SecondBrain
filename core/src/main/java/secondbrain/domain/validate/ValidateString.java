package secondbrain.domain.validate;

import jakarta.validation.constraints.NotNull;

public interface ValidateString {
    @NotNull
    String throwIfEmpty(String value);
}
