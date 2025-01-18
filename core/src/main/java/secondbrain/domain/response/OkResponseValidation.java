package secondbrain.domain.response;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.MissingResponse;

@ApplicationScoped
public class OkResponseValidation implements ResponseValidation {
    @Override
    public Response validate(final Response response) {
        if (response.getStatus() == 404) {
            throw new MissingResponse("Expected status code 200, but got 404");
        }

        if (response.getStatus() != 200) {
            throw new InvalidResponse("Expected status code 200, but got "
                    + response.getStatus(),
                    response.readEntity(String.class),
                    response.getStatus());
        }

        return response;
    }
}
