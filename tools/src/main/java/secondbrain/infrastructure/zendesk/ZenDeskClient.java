package secondbrain.infrastructure.zendesk;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;

import java.util.List;

@ApplicationScoped
public class ZenDeskClient {

    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(Constants.DEFAULT_SEMAPHORE_COUNT);

    /*
        Don't recurse forever, because LLMs can't deal with large results anyway.
     */
    private static final int MAX_PAGES = 5;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private LocalStorage localStorage;

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

        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("Query is required");
        }

        return getTickets(client, authorization, url, query, 1, MAX_PAGES);
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    private List<ZenDeskResultsResponse> getTickets(
            final Client client,
            final String authorization,
            final String url,
            final String query,
            final int page,
            final int maxPage) {
        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client))
                .of(sem -> getTicketsApi(sem.getWrapped(), authorization, url, query, page, maxPage))
                .get();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    private List<ZenDeskResultsResponse> getTicketsApi(
            final Client client,
            final String authorization,
            final String url,
            final String query,
            final int page,
            final int maxPage) {

        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("Query is required");
        }

        final String target = url + "/api/v2/search.json";

        return Try.of(() -> client.target(target)
                        .queryParam("query", query)
                        .queryParam("sort_by", "created_at")
                        .queryParam("sort_order", "desc")
                        .queryParam("page", page)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get())
                .map(response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(ZenDeskResponse.class))
                        // Recurse if there is a next page and we have not gone too far
                        .map(r -> ListUtils.union(
                                r.getResults(),
                                r.next_page() != null && page < maxPage
                                        ? getTicketsApi(client, authorization, url, query, page + 1, maxPage)
                                        : List.of()))
                        .get())
                .get();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    public ZenDeskTicketResponse getTicket(
            final Client client,
            final String authorization,
            final String url,
            final String id) {

        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("Ticket ID is required");
        }

        final String target = url + "/api/v2/tickets/" + id + ".json";

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(ZenDeskTicketResponse.class))
                        .get())
                .get();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    public ZenDeskCommentsResponse getComments(
            final Client client,
            final String authorization,
            final String url,
            final String ticketId) {

        if (StringUtils.isBlank(ticketId)) {
            throw new IllegalArgumentException("Ticket ID is required");
        }

        final String target = url + "/api/v2/tickets/" + ticketId + "/comments";

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(ZenDeskCommentsResponse.class))
                        .get())
                .get();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    public ZenDeskOrganizationResponse getOrganization(
            final Client client,
            final String authorization,
            final String url,
            final String orgId) {

        if (StringUtils.isBlank(orgId)) {
            throw new IllegalArgumentException("Organization ID is required");
        }

        final String target = url + "/api/v2/organizations/" + orgId;

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(ZenDeskOrganizationResponse.class))
                        .get())
                .get();
    }

    public ZenDeskOrganizationItemResponse getOrganizationCached(
            final Client client,
            final String authorization,
            final String url,
            final String orgId) {

        if (StringUtils.isBlank(orgId)) {
            throw new IllegalArgumentException("Organization ID is required");
        }

        return localStorage.getOrPutObject(
                ZenDeskClient.class.getSimpleName(),
                "ZenDeskAPIOrganizations",
                orgId,
                ZenDeskOrganizationResponse.class,
                () -> getOrganization(client, authorization, url, orgId)).organization();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    public ZenDeskUserResponse getUser(
            final Client client,
            final String authorization,
            final String url,
            final String userId) {

        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("User ID is required");
        }

        final String target = url + "/api/v2/users/" + userId;

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(ZenDeskUserResponse.class))
                        .get())
                .get();
    }

    public ZenDeskUserItemResponse getUserCached(
            final Client client,
            final String authorization,
            final String url,
            final String userId) {

        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("User ID is required");
        }

        return localStorage.getOrPutObject(
                ZenDeskClient.class.getSimpleName(),
                "ZenDeskAPIUsers",
                userId,
                ZenDeskUserResponse.class,
                () -> getUser(client, authorization, url, userId)).user();
    }
}
