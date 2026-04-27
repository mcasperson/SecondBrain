package secondbrain.domain.response;


import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;

public interface ResponseValidation {
    Response validate(Response response, String uri);
    Response validate(Response response, String uri, @Nullable String requestBody);
}
