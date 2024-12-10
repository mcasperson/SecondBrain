package secondbrain.infrastructure.publicweb;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.response.ResponseValidation;

@ApplicationScoped
public class PublicWebClient {
    @Inject
    private ResponseValidation responseValidation;

    @Retry
    public String getDocument(final Client client, final String url) {
        String newUrl = url;
        int count = 0;

        do {
            try (Response response = client.target(newUrl).request().get()) {
                if (response.getStatus() == 302 || response.getStatus() == 301) {
                    newUrl = response.getHeaderString("Location");
                } else {
                    return responseValidation.validate(response).readEntity(String.class);
                }
            }
            count++;
        } while (count < 5);

        throw new InvalidResponse("Failed to get document from " + url);
    }
}
