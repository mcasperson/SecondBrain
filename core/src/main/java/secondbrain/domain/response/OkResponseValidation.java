package secondbrain.domain.response;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.exceptions.UnauthorizedResponse;

@ApplicationScoped
public class OkResponseValidation implements ResponseValidation {
    @Override
    public Response validate(final Response response, final String uri) {
        if (response.getStatus() == 404) {
            throw new MissingResponse("Expected status code 200, but got 404. This likely indicates the requested resource was not found.");
        }

        if (response.getStatus() == 401) {
            throw new UnauthorizedResponse("Expected status code 200, but got 401. This likely indicates an authentication issue.");
        }

        if (response.getStatus() != 200 && response.getStatus() != 201) {
            final String responseBody = getResponseBody(response);
            throw new InvalidResponse("Expected status code 200, but got "
                    + response.getStatus()
                    + " from URI " + uri + ". " + responseBody,
                    responseBody,
                    response.getStatus());
        }

        return response;
    }

    private String getResponseBody(Response response) {
        return Try.of(() -> response.readEntity(String.class))
                .getOrElse("No response body available");
    }
}
