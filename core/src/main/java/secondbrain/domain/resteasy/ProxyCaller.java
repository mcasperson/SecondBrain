package secondbrain.domain.resteasy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.ClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;

@ApplicationScoped
public class ProxyCaller {
     public <T, U> U callProxy(@NotNull final String uri, @NotNull Class<T> proxyInterface, @NotNull ProxyCallback<T, U> callback) {
        try (ClosableResteasyClient client = new ClosableResteasyClient((ResteasyClient) ClientBuilder.newClient())) {
            final ResteasyWebTarget target = client.target(uri);
            return callback.callProxy(target.proxy(proxyInterface));
        } catch (final ClientErrorException ex) {
            System.out.println(ex.getMessage());
            throw ex;
        }
    }
}

class ClosableResteasyClient implements AutoCloseable {
    private final ResteasyClient client;

    ClosableResteasyClient(final ResteasyClient client) {
        this.client = client;
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    public ResteasyWebTarget target(final String uri) {
        return client.target(uri);
    }
}
