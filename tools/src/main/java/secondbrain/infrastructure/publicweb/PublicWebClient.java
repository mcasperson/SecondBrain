package secondbrain.infrastructure.publicweb;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.response.ResponseValidation;

@ApplicationScoped
public class PublicWebClient {
    @Inject
    private ResponseValidation responseValidation;

    @Retry
    public String getDocument(final Client client, final String url) {
        return Try.withResources(() -> client.target(url)
                        .request()
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(String.class))
                        .get())
                .get();
    }
}
