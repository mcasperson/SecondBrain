package secondbrain.domain.resteasy;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.ClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ProxyCaller {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyCaller.class);

    public <T, U> U callProxy(@NotNull final String uri, @NotNull Class<T> proxyInterface, @NotNull ProxyCallback<T, U> callback) {
        return Try.withResources(() -> new ClosableResteasyClient((ResteasyClient) ClientBuilder.newClient()))
                .of(client -> Try.of(() -> client.target(uri))
                        .map(target -> callback.callProxy(target.proxy(proxyInterface)))
                        .onFailure(ClientErrorException.class, ex -> LOGGER.error(ex.getMessage()))
                        .get())
                .get();
    }
}

class ClosableResteasyClient implements AutoCloseable {
    private final ResteasyClient client;

    ClosableResteasyClient(@NotNull final ResteasyClient client) {
        this.client = client;
    }

    @Override
    public void close() {
        client.close();
    }

    public ResteasyWebTarget target(final String uri) {
        return client.target(uri);
    }
}
