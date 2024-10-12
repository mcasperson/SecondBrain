package secondbrain.domain.response;

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.core.Response;

public interface ResponseValidation {
    @NotNull Response validate(@NotNull Response response);
}
