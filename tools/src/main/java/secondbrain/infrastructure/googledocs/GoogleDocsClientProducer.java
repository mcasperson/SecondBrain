package secondbrain.infrastructure.googledocs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * A factory that creates the GoogleDocsClient used by {@link secondbrain.domain.tools.googledocs.GoogleDocs}. Tests
 * replace this class with their own producer method that returns a test instance of GoogleDocsClient. There is no
 * disposer, as the live client creates and releases its transport for each request.
 */
@ApplicationScoped
public class GoogleDocsClientProducer {
    @Produces
    public GoogleDocsClient produceGoogleDocsClient() {
        return new GoogleDocsClientLive();
    }
}
