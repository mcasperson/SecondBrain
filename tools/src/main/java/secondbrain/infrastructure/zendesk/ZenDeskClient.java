package secondbrain.infrastructure.zendesk;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.Client;
import secondbrain.domain.response.ResponseValidation;

@ApplicationScoped
public class ZenDeskClient {

    @Inject
    private ResponseValidation responseValidation;

    @NotNull
    public ZenDeskResponse getTickets(
            @NotNull final Client client,
            @NotNull final String authorization,
            @NotNull final String url,
            @NotNull final String query) {

        return Try.withResources(() -> client.target(url + "/api/v2/search.json")
                        .queryParam("query", query)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(ZenDeskResponse.class))
                        .get())
                .get();
    }

    @NotNull
    public ZenDeskCommentsResponse getComments(
            @NotNull final Client client,
            @NotNull final String authorization,
            @NotNull final String url,
            @NotNull final String ticketId) {

        return Try.withResources(() -> client.target(url + "/api/v2/tickets/" + ticketId + "/comments")
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(ZenDeskCommentsResponse.class))
                        .get())
                .get();
    }
}
