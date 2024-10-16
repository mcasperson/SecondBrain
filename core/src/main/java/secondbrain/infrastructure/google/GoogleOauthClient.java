package secondbrain.infrastructure.google;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
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
public class GoogleOauthClient {
    public GoogleOauthTokenResponse exchangeToken(
            @NotNull final Client client,
            @NotNull final String code,
            @NotNull final String clientId,
            @NotNull final String clientSecret,
            @NotNull final String redirect) throws IOException {

        final List<EntityPart> multipart = new ArrayList<>();
        multipart.add(EntityPart.withName("code").content(code).build());
        multipart.add(EntityPart.withName("client_id").content(clientId).build());
        multipart.add(EntityPart.withName("client_secret").content(clientSecret).build());
        multipart.add(EntityPart.withName("redirect_uri").content(redirect).build());
        multipart.add(EntityPart.withName("access_type").content("offline").build());
        multipart.add(EntityPart.withName("grant_type").content("authorization_code").build());

        try (final Response response = client.target("https://oauth2.googleapis.com/token")
                .request(MediaType.MULTIPART_FORM_DATA_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.entity(new GenericEntity<>(multipart) {
                }, MediaType.MULTIPART_FORM_DATA))) {
            return response.readEntity(GoogleOauthTokenResponse.class);
        }
    }

    public GoogleOauthTokenResponse refresh(
            @NotNull final Client client,
            @NotNull final String refreshToken,
            @NotNull final String clientId,
            @NotNull final String clientSecret) throws IOException {

        final List<EntityPart> multipart = new ArrayList<>();
        multipart.add(EntityPart.withName("refresh_token").content(refreshToken).build());
        multipart.add(EntityPart.withName("client_id").content(clientId).build());
        multipart.add(EntityPart.withName("client_secret").content(clientSecret).build());
        multipart.add(EntityPart.withName("grant_type").content("refresh_token").build());

        try (final Response response = client.target("https://oauth2.googleapis.com/token")
                .request(MediaType.MULTIPART_FORM_DATA_TYPE)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.entity(new GenericEntity<>(multipart) {
                }, MediaType.MULTIPART_FORM_DATA))) {
            return response.readEntity(GoogleOauthTokenResponse.class);
        }
    }
}
