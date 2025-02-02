package secondbrain.infrastructure.planhat;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;

import java.util.List;

@ApplicationScoped
public class PlanHatClient {
    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @ConfigProperty(name = "sb.planhat.url", defaultValue = "https://api-us4.planhat.com")
    private String url;

    @Inject
    private LocalStorage localStorage;

    public List<Conversation> getConversations(
            final Client client,
            final String company,
            final String token,
            final int ttlSeconds) {
        final Conversation[] conversations = localStorage.getOrPutObject(
                PlanHatClient.class.getSimpleName(),
                "PlanHatAPIConversations",
                company,
                ttlSeconds,
                Conversation[].class,
                () -> getConversationsApi(client, company, token));

        return conversations == null
                ? List.of()
                : List.of(conversations);
    }

    @Retry
    private Conversation[] getConversationsApi(final Client client, final String company, final String token) {
        final String target = url + "/conversations";
        return Try.withResources(() -> client.target(target)
                        .queryParam("cId", company)
                        .request()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(Conversation[].class))
                        .get())
                .get();
    }
}