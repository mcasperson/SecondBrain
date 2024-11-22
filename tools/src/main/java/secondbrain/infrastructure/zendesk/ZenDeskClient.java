package secondbrain.infrastructure.zendesk;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.apache.commons.collections4.ListUtils;
import secondbrain.domain.response.ResponseValidation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ZenDeskClient {
    /*
        Don't recurse forever, because LLMs can't deal with large results anyway.
     */
    private static final int MAX_PAGES = 5;

    private static final Map<String, String> ORGANIZATION_CACHE = new HashMap<>();

    @Inject
    private ResponseValidation responseValidation;


    public List<ZenDeskResultsResponse> getTickets(
            final Client client,
            final String authorization,
            final String url,
            final String query) {

        return getTickets(client, authorization, url, query, 1, MAX_PAGES);
    }

    private List<ZenDeskResultsResponse> getTickets(
            final Client client,
            final String authorization,
            final String url,
            final String query,
            final int page,
            final int maxPage) {

        return Try.withResources(() -> client.target(url + "/api/v2/search.json")
                        .queryParam("query", query)
                        .queryParam("sort_by", "created_at")
                        .queryParam("sort_order", "desc")
                        .queryParam("page", page)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(ZenDeskResponse.class))
                        // Recurse if there is a next page and we have not gone too far
                        .map(r -> ListUtils.union(
                                r.results(),
                                r.next_page() != null && page < maxPage
                                        ? getTickets(client, authorization, url, query, page + 1, maxPage)
                                        : List.of()))
                        .get())
                .get();
    }

    public ZenDeskTicketResponse getTicket(
            final Client client,
            final String authorization,
            final String url,
            final String id) {

        return Try.withResources(() -> client.target(url + "/api/v2/tickets/" + id + ".json")
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(ZenDeskTicketResponse.class))
                        .get())
                .get();
    }


    public ZenDeskCommentsResponse getComments(
            final Client client,
            final String authorization,
            final String url,
            final String ticketId) {

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

    public ZenDeskOrganizationResponse getOrganization(
            final Client client,
            final String authorization,
            final String url,
            final String orgId) {

        return Try.withResources(() -> client.target(url + "/api/v2/organizations/" + orgId)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(ZenDeskOrganizationResponse.class))
                        .get())
                .get();
    }

    public ZenDeskOrganizationItemResponse getOrganizationCached(
            final Client client,
            final String authorization,
            final String url,
            final String orgId) {

        if (ORGANIZATION_CACHE.containsKey(orgId)) {
            return new ZenDeskOrganizationItemResponse(ORGANIZATION_CACHE.get(orgId), orgId);
        }

        final ZenDeskOrganizationResponse org = getOrganization(client, authorization, url, orgId);

        ORGANIZATION_CACHE.put(orgId, org.organization().name());

        return org.organization();
    }
}
