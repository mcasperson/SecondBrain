package secondbrain.domain.response.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.core.Response;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.response.ResponseValidation;

@ApplicationScoped
@Named("OkValidator")
public class OkResponseValidation implements ResponseValidation {
    @Override
    @NotNull public Response validate(@NotNull final Response response) {
        if (response.getStatus() == 404) {
            throw new MissingResponse("Expected status code 200, but got 404");
        }

        if (response.getStatus() != 200) {
            throw new InvalidResponse("Expected status code 200, but got " + response.getStatus());
        }

        return response;
    }
}
