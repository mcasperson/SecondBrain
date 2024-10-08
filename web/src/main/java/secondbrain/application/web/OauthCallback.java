package secondbrain.application.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import org.jasypt.util.text.BasicTextEncryptor;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.infrastructure.oauth.OauthClient;
import secondbrain.infrastructure.oauth.OauthTokenResponse;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Path("/slack_oauth")
public class OauthCallback {
    @Inject
    private OauthClient oauthClient;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @GET
    public Response get(@QueryParam("code") final String code, @QueryParam("state") final String state) {
        return Try.of(() -> oauthClient.exchangeToken(ClientBuilder.newClient(),
                        code,
                        System.getenv("SLACK_CLIENT_ID"),
                        System.getenv("SLACK_CLIENT_SECRET")))
                .map(OauthTokenResponse::access_token)
                .mapTry(accessToken -> redirectWithToken(accessToken, state))
                .recover(this::redirectToLoginFailed)
                .get();
    }

    private Response redirectToLoginFailed(@NotNull final Throwable error) {
        return Response.temporaryRedirect(Try.of(() -> new URI("/login_failed.html")).get()).build();
    }

    private Response redirectWithToken(@NotNull final String accessToken, @NotNull final String state) throws JsonProcessingException {
        final String accessTokenEncrypted = new BasicTextEncryptor().encrypt(accessToken);

        final Map<String, String> stateCookie = new HashMap<>();
        stateCookie.put("access_token", accessTokenEncrypted);

        final String stateCookieString = jsonDeserializer.serialize(stateCookie);

        final Response.ResponseBuilder builder = Response.temporaryRedirect(Try.of(() -> new URI(state)).get());
        builder.header("Set-Cookie", "access_token=" + stateCookieString);

        return builder.build();
    }
}
