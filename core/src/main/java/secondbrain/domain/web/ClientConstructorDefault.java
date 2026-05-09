package secondbrain.domain.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;

import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ClientConstructorDefault implements ClientConstructor {

    private static final int API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 30;
    private static final int API_READ_TIMEOUT_SECONDS_DEFAULT = 120;

    @Override
    public Client getClient() {
        return getClient(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, API_READ_TIMEOUT_SECONDS_DEFAULT);
    }

    @Override
    public Client getClient(final int connectionTimeout, final int readTimeout) {
        return ClientBuilder.newBuilder()
            .connectTimeout(connectionTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .build();
    }

    @Override
    public Client getClient(final int readTimeout) {
        return getClient(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, readTimeout);
    }
}
