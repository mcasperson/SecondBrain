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

        try (final Response response = client.target("https://slack.com/api/oauth.v2.access")
                .request(MediaType.MULTIPART_FORM_DATA_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.entity(new GenericEntity<>(multipart) {
                }, MediaType.MULTIPART_FORM_DATA))) {
            return response.readEntity(OauthTokenResponse.class);
        }
    }
}
