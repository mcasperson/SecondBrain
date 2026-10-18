package secondbrain.domain.test;

import jakarta.ws.rs.client.Client;
import secondbrain.domain.web.ClientConstructor;

/**
 * Always returns the supplied client, regardless of the requested timeouts.
 */
public final class FixedClientConstructor implements ClientConstructor {
    private final Client client;

    public FixedClientConstructor(final Client client) {
        this.client = client;
    }

    @Override
    public Client getClient() {
        return client;
    }

    @Override
    public Client getClient(final int connectionTimeout, final int readTimeout) {
        return client;
    }

    @Override
    public Client getClient(final int readTimeout) {
        return client;
    }
}
