package secondbrain.domain.httpclient;

import jakarta.ws.rs.core.Response;

public interface ResponseCallback<T> {
    T handleResponse(Response response);
}
