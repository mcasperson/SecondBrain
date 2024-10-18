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
import org.jasypt.util.text.BasicTextEncryptor;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.infrastructure.oauth.slack.SlackOauthClient;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Path("/slack_oauth")
public class SlackOauthCallback {
    @Inject
    @ConfigProperty(name = "sb.slack.clientid")
    Optional<String> slackClientId;

    @Inject
    @ConfigProperty(name = "sb.slack.clientsecret")
    Optional<String> slackClientSecret;

    @Inject
    private SlackOauthClient oauthClient;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @GET
    public Response get(@QueryParam("code") final String code, @QueryParam("state") final String state) {
        if (slackClientId.isEmpty() || slackClientSecret.isEmpty()) {
            return Response.serverError()
                    .entity("Slack client id or secret must be set via the \"sb.slack.clientid\" and \"sb.slack.clientsecret\" configuration values.")
                    .build();
        }

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> oauthClient.exchangeToken(
                        client,
                        code,
                        slackClientId.get(),
                        slackClientSecret.get()))
                .map(response -> response.authed_user().access_token())
                .mapTry(accessToken -> redirectWithToken(accessToken, state))
                .onFailure(error -> System.out.println("Error: " + error.getMessage()))
                .recover(this::redirectToLoginFailed)
                .get();
    }

    private Response redirectToLoginFailed(final Throwable error) {
        return Response.temporaryRedirect(Try.of(() -> new URI("/login_failed.html")).get()).build();
    }

    private Response redirectWithToken(final String accessToken, final String state) throws JsonProcessingException {
        final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(System.getenv("ENCRYPTION_PASSWORD"));

        final String accessTokenEncrypted = textEncryptor.encrypt(accessToken);

        final Map<String, String> stateCookie = new HashMap<>();
        stateCookie.put("slack_access_token", accessTokenEncrypted);

        final String stateCookieString = jsonDeserializer.serialize(stateCookie);

        final String base64EncodedCookie = new String(Try.of(() -> new Base64().encode(
                stateCookieString.getBytes())).get());

        final Response.ResponseBuilder builder = Response.temporaryRedirect(Try.of(() -> new URI(state)).get());
        builder.header("Set-Cookie", "session=" + base64EncodedCookie + ";path=/");

        return builder.build();
    }
}
