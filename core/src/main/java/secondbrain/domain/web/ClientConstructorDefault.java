package secondbrain.domain.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;

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
        final jakarta.ws.rs.client.ClientBuilder clientBuilder = jakarta.ws.rs.client.ClientBuilder.newBuilder();
        clientBuilder.connectTimeout(connectionTimeout, TimeUnit.SECONDS);
        clientBuilder.readTimeout(readTimeout, TimeUnit.SECONDS);
        return clientBuilder.build();
    }
}
