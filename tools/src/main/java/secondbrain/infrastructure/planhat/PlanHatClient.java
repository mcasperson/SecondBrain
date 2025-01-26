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
        return List.of(localStorage.getOrPutObject(
                PlanHatClient.class.getSimpleName(),
                "PlanHatAPIConversations",
                company,
                ttlSeconds,
                Conversation[].class,
                () -> getConversationsApi(client, company, token)));
    }

    @Retry
    private Conversation[] getConversationsApi(final Client client, final String company, final String token) {
        return Try.withResources(() -> client.target(url + "/conversations")
                        .queryParam("cId", company)
                        .request()
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(Conversation[].class))
                        .get())
                .get();
    }
}