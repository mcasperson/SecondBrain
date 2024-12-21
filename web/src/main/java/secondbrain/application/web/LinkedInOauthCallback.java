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
import secondbrain.infrastructure.oauth.linkedin.LinkedInOauthTokenResponse;
import secondbrain.infrastructure.oauth.linkedin.LinkedinOauthClient;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Path("/linkedin_oauth")
public class LinkedInOauthCallback {
    @Inject
    @ConfigProperty(name = "sb.linkedin.clientid")
    private Optional<String> linkedinClientId;

    @Inject
    @ConfigProperty(name = "sb.linkedin.clientsecret")
    private Optional<String> linkedinClientSecret;

    @Inject
    @ConfigProperty(name = "sb.linkedin.redirecturi")
    private Optional<String> linkedinRedirectUri;

    @Inject
    private LinkedinOauthClient oauthClient;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Inject
    private Encryptor textEncryptor;

    @GET
    public Response get(@QueryParam("code") final String code, @QueryParam("state") final String state) {
        if (linkedinClientId.isEmpty() || linkedinClientSecret.isEmpty() || linkedinRedirectUri.isEmpty()) {
            return Response.serverError()
                    .entity("LinkedIn client id or secret must be set via the \"sb.linkedin.redirecturi\", \"sb.linkedin.clientid\", and \"sb.linkedin.clientsecret\" configuration values.")
                    .build();
        }

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> oauthClient.exchangeToken(
                        client,
                        linkedinRedirectUri.get(),
                        code,
                        linkedinClientId.get(),
                        linkedinClientSecret.get()))
                .map(LinkedInOauthTokenResponse::access_token)
                .mapTry(accessToken -> redirectWithToken(accessToken, state))
                .onFailure(error -> System.out.println("Error: " + error.getMessage()))
                .recover(this::redirectToLoginFailed)
                .get();
    }

    private Response redirectToLoginFailed(final Throwable error) {
        return Response.temporaryRedirect(Try.of(() -> new URI("/login_failed.html")).get()).build();
    }

    private Response redirectWithToken(final String accessToken, final String state) throws JsonProcessingException {
        final String accessTokenEncrypted = textEncryptor.encrypt(accessToken);

        final Map<String, String> stateCookie = new HashMap<>();
        stateCookie.put("linkedin_access_token", accessTokenEncrypted);

        final String stateCookieString = jsonDeserializer.serialize(stateCookie);

        final String base64EncodedCookie = new String(Try.of(() -> new Base64().encode(
                stateCookieString.getBytes())).get());

        final Response.ResponseBuilder builder = Response.temporaryRedirect(Try.of(() -> new URI(state)).get());
        builder.header("Set-Cookie", "session=" + base64EncodedCookie + ";path=/");

        return builder.build();
    }
}
