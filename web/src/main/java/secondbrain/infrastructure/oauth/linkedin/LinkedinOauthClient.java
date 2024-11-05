package secondbrain.infrastructure.oauth.linkedin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.IOException;

@ApplicationScoped
public class LinkedinOauthClient {
    public LinkedInOauthTokenResponse exchangeToken(final Client client, final String redirectUri, final String code, final String clientId, final String clientSecret) throws IOException {

        final MultivaluedMap<String, String> body = new MultivaluedHashMap<>();
        body.add("grant_type", "authorization_code");
        body.add("redirect_uri", redirectUri);
        body.add("code", code);
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);

        try (final Response response = client.target("https://www.linkedin.com/oauth/v2/accessToken")
                .request(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.entity(body, MediaType.APPLICATION_FORM_URLENCODED))) {
            return response.readEntity(LinkedInOauthTokenResponse.class);
        }
    }
}
