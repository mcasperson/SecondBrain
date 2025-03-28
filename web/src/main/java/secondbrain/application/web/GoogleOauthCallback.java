package secondbrain.application.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import org.apache.commons.codec.binary.Base64;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.infrastructure.oauth.google.GoogleOauthClient;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Based on https://developers.google.com/identity/protocols/oauth2/web-server#httprest_2
 */
@Path("/google_oauth")
public class GoogleOauthCallback {
    @Inject
    @ConfigProperty(name = "sb.google.clientid")
    private Optional<String> googleClientId;

    @Inject
    @ConfigProperty(name = "sb.google.clientsecret")
    private Optional<String> googleClientSecret;

    @Inject
    @ConfigProperty(name = "sb.google.redirecturl", defaultValue = "https://localhost:8181/api/google_oauth")
    private Optional<String> googleRedirectUrl;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private GoogleOauthClient oauthClient;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @GET
    public Response get(@QueryParam("code") final String code, @QueryParam("state") final String state) {
        if (googleClientId.isEmpty() || googleClientSecret.isEmpty() || googleRedirectUrl.isEmpty()) {
            return Response.serverError()
                    .entity("Google client id, secret, and redirect urls must be set via the \"sb.google.clientid\", \"sb.google.clientsecret\", and \"sb.google.redirecturl\" configuration values.")
                    .build();
        }

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> oauthClient.exchangeToken(
                        client,
                        code,
                        googleClientId.get(),
                        googleClientSecret.get(),
                        googleRedirectUrl.get()))
                .mapTry(accessToken -> redirectWithToken(accessToken.access_token(), accessToken.expires_in(), state))
                .onFailure(error -> System.out.println("Error: " + error.getMessage()))
                .recover(this::redirectToLoginFailed)
                .get();
    }

    private Response redirectToLoginFailed(final Throwable error) {
        return Response.temporaryRedirect(Try.of(() -> new URI("/login_failed.html")).get()).build();
    }

    private Response redirectWithToken(final String accessToken, final int expiresIn, final String state) throws JsonProcessingException {
        final String accessTokenEncrypted = textEncryptor.encrypt(accessToken);

        final Map<String, String> stateCookie = new HashMap<>();
        stateCookie.put("google_access_token", accessTokenEncrypted);
        stateCookie.put("google_access_token_expires", LocalDateTime.now().plusSeconds(expiresIn).toEpochSecond(ZoneOffset.UTC) + "");

        final String stateCookieString = jsonDeserializer.serialize(stateCookie);

        final String base64EncodedCookie = new String(Try.of(() -> new Base64().encode(
                stateCookieString.getBytes())).get());

        final Response.ResponseBuilder builder = Response.temporaryRedirect(Try.of(() -> new URI(state)).get());
        builder.header("Set-Cookie", "session=" + base64EncodedCookie + ";path=/");

        return builder.build();
    }
}
