package secondbrain.domain.web;

import jakarta.ws.rs.client.Client;

public interface ClientConstructor {
    Client getClient();
    Client getClient(int connectionTimeout, int readTimeout);
}
