package secondbrain.domain.httpclient;

import jakarta.ws.rs.client.Client;

public interface ClientBuilder {
    Client buildClient();
}
