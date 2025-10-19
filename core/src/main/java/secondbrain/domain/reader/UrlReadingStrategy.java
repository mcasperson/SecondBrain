package secondbrain.domain.reader;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.ArrayUtils;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.response.ResponseValidation;

@ApplicationScoped
public class UrlReadingStrategy implements FileReadingStrategy {
    private static final int REDIRECT_LIMIT = 5;
    private static final int[] REDIRECT_STATUSES = {301, 302};
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(Constants.DEFAULT_SEMAPHORE_COUNT);

    @Inject
    private ResponseValidation responseValidation;

    private String downloadDocument(final Client client, final String url) {
        return Try.withResources(SEMAPHORE_LENDER::lend)
                .of(s -> downloadDocumentLoop(client, url, 0))
                .get();
    }

    private String downloadDocumentLoop(final Client client, final String url, int retry) {
        if (retry > REDIRECT_LIMIT) {
            throw new InvalidResponse("Failed to get document from " + url);
        }

        return Try.withResources(() -> client.target(url).request().get())
                .of(response -> {
                    if (ArrayUtils.contains(REDIRECT_STATUSES, response.getStatus())) {
                        return downloadDocumentLoop(client, response.getHeaderString("Location"), retry + 1);
                    } else {
                        return responseValidation.validate(response, url).readEntity(String.class);
                    }
                })
                .get();
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
