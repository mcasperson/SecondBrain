package secondbrain.domain.response;


import jakarta.ws.rs.core.Response;

public interface ResponseValidation {
    Response validate(Response response);
}
