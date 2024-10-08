package secondbrain.infrastructure.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class OauthClient {
    public OauthTokenResponse exchangeToken(Client client, String code, String clientId, String clientSecret) throws IOException {

        final List<EntityPart> multipart = new ArrayList<>();
        multipart.add(EntityPart.withName("code").content(code).build());
        multipart.add(EntityPart.withName("client_id").content(clientId).build());
        multipart.add(EntityPart.withName("client_secret").content(clientSecret).build());

        try(final Response response = client.target("http://localhost:8080/form")
                .request(MediaType.MULTIPART_FORM_DATA_TYPE)
                .post(Entity.entity(new GenericEntity<>(multipart) {}, MediaType.MULTIPART_FORM_DATA))) {
            return response.readEntity(OauthTokenResponse.class);
        }
    }
}
