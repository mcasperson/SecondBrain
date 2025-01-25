package secondbrain.infrastructure.zendesk;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.apache.commons.collections4.ListUtils;
import org.eclipse.microprofile.faulttolerance.Retry;
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
    private static final Map<String, String> USER_CACHE = new HashMap<>();

    @Inject
    private ResponseValidation responseValidation;

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10)
    public List<ZenDeskResultsResponse> getTickets(
            final Client client,
            final String authorization,
            final String url,
            final String query) {

        return getTickets(client, authorization, url, query, 1, MAX_PAGES);
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10)
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
                                r.getResults(),
                                r.next_page() != null && page < maxPage
                                        ? getTickets(client, authorization, url, query, page + 1, maxPage)
                                        : List.of()))
                        .get())
                .get();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10)
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

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10)
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

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10)
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

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10)
    public ZenDeskUserResponse getUser(
            final Client client,
            final String authorization,
            final String url,
            final String userId) {

        return Try.withResources(() -> client.target(url + "/api/v2/users/" + userId)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(ZenDeskUserResponse.class))
                        .get())
                .get();
    }

    public ZenDeskUserItemResponse getUserCached(
            final Client client,
            final String authorization,
            final String url,
            final String userId) {

        if (USER_CACHE.containsKey(userId)) {
            return new ZenDeskUserItemResponse(USER_CACHE.get(userId), userId);
        }

        final ZenDeskUserResponse org = getUser(client, authorization, url, userId);

        USER_CACHE.put(userId, org.user().name());

        return org.user();
    }
}
