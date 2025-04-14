package secondbrain.infrastructure.zendesk;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.zendesk.api.*;

import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class ZenDeskClientLive implements ZenDeskClient {

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
    @Override
    public List<ZenDeskResultsResponse> getTickets(
            final Client client,
            final String authorization,
            final String url,
            final String query,
            final int ttlSeconds) {

        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("Query is required");
        }

        return getTickets(client, authorization, url, query, 1, MAX_PAGES, ttlSeconds);
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     * This method is synchronized to have one tool populate the cache and let the others read from it.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    public List<ZenDeskResultsResponse> getTickets(
            Client client,
            String authorization,
            String url,
            String query,
            int page,
            int maxPage,
            int ttlSeconds) {

        final ZenDeskResultsResponse[] value = localStorage.getOrPutObject(
                ZenDeskClientLive.class.getSimpleName(),
                "ZenDeskApiTickets",
                "Global",
                ttlSeconds,
                ZenDeskResultsResponse[].class,
                () -> getTicketsApi(client, authorization, url, query, page, maxPage));

        return Arrays.asList(value);
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    private ZenDeskResultsResponse[] getTicketsApi(
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

        return Try.withResources(() -> client.target(target)
                        .queryParam("query", query)
                        .queryParam("sort_by", "created_at")
                        .queryParam("sort_order", "desc")
                        .queryParam("page", page)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(ZenDeskResponse.class))
                        // Recurse if there is a next page, and we have not gone too far
                        .map(r -> ArrayUtils.addAll(
                                r.getResultsArray(),
                                r.next_page() != null && page < maxPage
                                        ? getTicketsApi(client, authorization, url, query, page + 1, maxPage)
                                        : new ZenDeskResultsResponse[]{}))
                        .get())
                .get();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    private ZenDeskTicketResponse getTicketFromApi(
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
    @Override
    public ZenDeskCommentsResponse getComments(
            final Client client,
            final String authorization,
            final String url,
            final String ticketId,
            int ttlSeconds) {

        if (StringUtils.isBlank(ticketId)) {
            throw new IllegalArgumentException("Ticket ID is required");
        }

        return localStorage.getOrPutObject(
                ZenDeskClientLive.class.getSimpleName(),
                "ZenDeskApiComments",
                ticketId,
                0,
                ZenDeskCommentsResponse.class,
                () -> getCommentsFromApi(client, authorization, url, ticketId));
    }

    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    private ZenDeskCommentsResponse getCommentsFromApi(
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
    private ZenDeskOrganizationResponse getOrganizationFromApi(
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

    @Override
    public ZenDeskOrganizationItemResponse getOrganization(
            final Client client,
            final String authorization,
            final String url,
            final String orgId) {

        if (StringUtils.isBlank(orgId)) {
            throw new IllegalArgumentException("Organization ID is required");
        }

        return localStorage.getOrPutObject(
                ZenDeskClientLive.class.getSimpleName(),
                "ZenDeskAPIOrganizations",
                orgId,
                ZenDeskOrganizationResponse.class,
                () -> getOrganizationFromApi(client, authorization, url, orgId)).organization();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Retry(delay = 30000, maxRetries = 10, abortOn = {IllegalArgumentException.class})
    private ZenDeskUserResponse getUserFromApi(
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

    @Override
    public ZenDeskUserItemResponse getUser(
            final Client client,
            final String authorization,
            final String url,
            final String userId) {

        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("User ID is required");
        }

        return localStorage.getOrPutObject(
                ZenDeskClientLive.class.getSimpleName(),
                "ZenDeskAPIUsers",
                userId,
                ZenDeskUserResponse.class,
                () -> getUserFromApi(client, authorization, url, userId)).user();
    }


}
