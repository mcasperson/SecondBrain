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
        // See here for the properties
        // https://cxf.apache.org/docs/client-http-transport-including-ssl-support.html#ClientHTTPTransport(includingSSLsupport)-Theclientelement

        return Try.withResources(() -> client.target(url)
                        .request()
                        .property("client.AutoRedirect", "true")
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(String.class))
                        .get())
                .get();
    }
}
