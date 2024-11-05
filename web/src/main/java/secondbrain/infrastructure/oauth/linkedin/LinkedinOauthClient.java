package secondbrain.infrastructure.oauth.linkedin;

import com.google.common.collect.ImmutableList;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.List;

@ApplicationScoped
public class LinkedinOauthClient {
    public LinkedInOauthTokenResponse exchangeToken(final Client client, final String redirectUri, final String code, final String clientId, final String clientSecret) throws IOException {

        final List<EntityPart> multipart = ImmutableList.of(
                EntityPart.withName("grant_type").content("authorization_code").build(),
                EntityPart.withName("redirect_uri").content(redirectUri).build(),
                EntityPart.withName("code").content(code).build(),
                EntityPart.withName("client_id").content(clientId).build(),
                EntityPart.withName("client_secret").content(clientSecret).build());

        try (final Response response = client.target("https://www.linkedin.com/oauth/v2/accessToken")
                .request(MediaType.MULTIPART_FORM_DATA_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.entity(new GenericEntity<>(multipart) {
                }, MediaType.MULTIPART_FORM_DATA))) {
            return response.readEntity(LinkedInOauthTokenResponse.class);
        }
    }
}
