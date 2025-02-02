package secondbrain.domain.reader;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.response.ResponseValidation;

@ApplicationScoped
public class UrlReadingStrategy implements FileReadingStrategy {
    @Inject
    private ResponseValidation responseValidation;

    private String downloadDocument(final Client client, final String url) {
        String newUrl = url;
        int count = 0;

        do {
            try (Response response = client.target(newUrl).request().get()) {
                if (response.getStatus() == 302 || response.getStatus() == 301) {
                    newUrl = response.getHeaderString("Location");
                } else {
                    return responseValidation.validate(response, newUrl).readEntity(String.class);
                }
            }
            count++;
        } while (count < 5);

        throw new InvalidResponse("Failed to get document from " + url);
    }

    @Override
    public String read(final String pathOrUrl) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> downloadDocument(client, pathOrUrl))
                .get();
    }

    @Override
    public boolean isSupported(final String pathOrUrl) {
        return Try.of(() -> new java.net.URI(pathOrUrl)).isSuccess();
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
