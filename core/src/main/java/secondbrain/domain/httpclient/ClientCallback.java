package secondbrain.domain.httpclient;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;

public interface ClientCallback {
    Response call(final Client client);
}
