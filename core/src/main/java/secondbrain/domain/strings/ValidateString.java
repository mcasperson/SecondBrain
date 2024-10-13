package secondbrain.domain.strings;

import jakarta.validation.constraints.NotNull;

public interface ValidateString {
    @NotNull
    String throwIfEmpty(String value);
}
