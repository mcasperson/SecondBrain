package secondbrain.infrastructure.zendesk;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptions.Timeout;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.zendesk.api.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@ApplicationScoped
public class ZenDeskClientLive implements ZenDeskClient {

    private static final RateLimiter RATE_LIMITER = RateLimiter.create(1);
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 2; // 2 minutes
    private static final long API_CALL_DELAY_SECONDS_DEFAULT = 30;
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final int API_RETRIES = 3;
    private static final String API_CALL_TIMEOUT_MESSAGE = "Call timed out after " + API_CALL_TIMEOUT_SECONDS_DEFAULT + " seconds";

    /*
        Don't recurse forever, because LLMs can't deal with large results anyway.
        Any more than 10 pages is invalid anyway (https://developer.zendesk.com/api-reference/ticketing/ticket-management/search/#results-limit).
        You'll get a message like this:
        {
            "error": "invalid",
            "description": "Invalid search: Requested response size was greater than Search Response Limits"
        }
     */
    private static final int MAX_PAGES = 10;
    private static final long MUTEX_TIMEOUT_MS = 30 * 60 * 1000;

    @Inject
    @ConfigProperty(name = "sb.zendesk.lock", defaultValue = "sb_zendesk.lock")
    private String lockFile;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private TimeoutHttpClientCaller httpClientCaller;

    @Inject
    @Preferred
    private Mutex mutex;

    @Inject
    private Logger logger;

    private Client getClient() {
        final ClientBuilder clientBuilder = ClientBuilder.newBuilder();
        clientBuilder.connectTimeout(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, TimeUnit.SECONDS);
        // We want to use the timeoutService to handle timeouts, so we set the client timeout slightly longer.
        clientBuilder.readTimeout(API_CALL_TIMEOUT_SECONDS_DEFAULT + CLIENT_TIMEOUT_BUFFER_SECONDS, TimeUnit.SECONDS);
        return clientBuilder.build();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Override
    public List<ZenDeskTicket> getTickets(
            final String authorization,
            final String url,
            final String query,
            final int ttlSeconds) {

        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("Query is required");
        }

        return getTickets(authorization, url, query, 1, MAX_PAGES, ttlSeconds);
    }

    @Override
    public ZenDeskTicket getTicket(final String authorization, final String url, final String ticketId, final int ttlSeconds) {
        return localStorage.getOrPutObject(
                ZenDeskClientLive.class.getSimpleName(),
                "ZenDeskApiTicket",
                DigestUtils.sha256Hex(url + ticketId),
                ttlSeconds,
                ZenDeskTicket.class,
                () -> getTicketApi(authorization, url, ticketId).ticket()).result();
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     * This method is synchronized to have one tool populate the cache and let the others read from it.
     */
    private List<ZenDeskTicket> getTickets(
            String authorization,
            String url,
            String query,
            int page,
            int maxPage,
            int ttlSeconds) {

        final ZenDeskTicket[] value = localStorage.getOrPutObject(
                ZenDeskClientLive.class.getSimpleName(),
                "ZenDeskApiTicketsV2",
                DigestUtils.sha256Hex(url + query + maxPage),
                ttlSeconds,
                ZenDeskTicket[].class,
                () -> getTicketsApi(authorization, url, query, page, maxPage)).result();

        return Arrays.asList(value);
    }

    private ZenDeskTicket[] getTicketsApi(
            final String authorization,
            final String url,
            final String query,
            final int page,
            final int maxPage) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".tickets",
                () -> getTicketsApiLocked(authorization, url, query, page, maxPage));
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    private ZenDeskTicket[] getTicketsApiLocked(
            final String authorization,
            final String url,
            final String query,
            final int page,
            final int maxPage) {

        if (StringUtils.isBlank(query)) {
            throw new IllegalArgumentException("Query is required");
        }

        logger.fine("Getting ZenDesk tickets, page " + page + " for query: " + query);

        RATE_LIMITER.acquire();

        final String target = url + "/api/v2/search.json";

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .queryParam("query", query)
                        .queryParam("sort_by", "created_at")
                        .queryParam("sort_order", "desc")
                        .queryParam("page", page)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get(),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(ZenDeskResponse.class))
                        // Recurse if there is a next page, and we have not gone too far
                        .map(r -> ArrayUtils.addAll(
                                r.getResultsArray(),
                                r.next_page() != null && page < maxPage
                                        ? getTicketsApiLocked(authorization, url, query, page + 1, maxPage)
                                        : new ZenDeskTicket[]{}))
                        .get(),
                e -> new RuntimeException("Failed to get comments from ZenDesk API", e),
                () -> {
                    throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                },
                API_CALL_TIMEOUT_SECONDS_DEFAULT,
                API_CALL_DELAY_SECONDS_DEFAULT,
                API_RETRIES);
    }

    private ZenDeskTicketResponse getTicketApi(
            final String authorization,
            final String url,
            final String id) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".ticket",
                () -> getTicketApiLocked(authorization, url, id));
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    private ZenDeskTicketResponse getTicketApiLocked(
            final String authorization,
            final String url,
            final String id) {

        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("Ticket ID is required");
        }

        logger.fine("Getting ZenDesk ticket ID: " + id);

        RATE_LIMITER.acquire();

        final String target = url + "/api/v2/tickets/" + id + ".json";

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get(),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(ZenDeskTicketResponse.class))
                        .get(),
                e -> new RuntimeException("Failed to get comments from ZenDesk API", e),
                () -> {
                    throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                },
                API_CALL_TIMEOUT_SECONDS_DEFAULT,
                API_CALL_DELAY_SECONDS_DEFAULT,
                API_RETRIES);
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    @Override
    public ZenDeskCommentsResponse getComments(
            final String authorization,
            final String url,
            final String ticketId,
            int ttlSeconds) {

        if (StringUtils.isBlank(ticketId)) {
            throw new IllegalArgumentException("Ticket ID is required");
        }

        return localStorage.getOrPutObject(
                ZenDeskClientLive.class.getSimpleName(),
                "ZenDeskApiCommentsV2",
                DigestUtils.sha256Hex(ticketId + url),
                0,
                ZenDeskCommentsResponse.class,
                () -> getCommentsFromApi(authorization, url, ticketId)).result();
    }

    private ZenDeskCommentsResponse getCommentsFromApi(
            final String authorization,
            final String url,
            final String ticketId) {
        return mutex.acquire(MUTEX_TIMEOUT_MS, lockFile, () -> getCommentsFromApiLocked(authorization, url, ticketId));
    }

    private ZenDeskCommentsResponse getCommentsFromApiLocked(
            final String authorization,
            final String url,
            final String ticketId) {

        if (StringUtils.isBlank(ticketId)) {
            throw new IllegalArgumentException("Ticket ID is required");
        }

        logger.fine("Getting ZenDesk comments for ticket ID: " + ticketId);

        RATE_LIMITER.acquire();

        final String target = url + "/api/v2/tickets/" + ticketId + "/comments";

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get(),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(ZenDeskCommentsResponse.class))
                        .get(),
                e -> new RuntimeException("Failed to get comments from ZenDesk API", e),
                () -> {
                    throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                },
                API_CALL_TIMEOUT_SECONDS_DEFAULT,
                API_CALL_DELAY_SECONDS_DEFAULT,
                API_RETRIES
        );
    }

    private ZenDeskOrganizationResponse getOrganizationFromApi(
            final String authorization,
            final String url,
            final String orgId) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".organization",
                () -> getOrganizationFromApiLocked(authorization, url, orgId));
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    private ZenDeskOrganizationResponse getOrganizationFromApiLocked(
            final String authorization,
            final String url,
            final String orgId) {

        if (StringUtils.isBlank(orgId)) {
            throw new IllegalArgumentException("Organization ID is required");
        }

        logger.fine("Getting ZenDesk organization ID: " + orgId);

        RATE_LIMITER.acquire();

        final String target = url + "/api/v2/organizations/" + orgId;

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get(),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(ZenDeskOrganizationResponse.class))
                        .get(),
                e -> new RuntimeException("Failed to get comments from ZenDesk API", e),
                () -> {
                    throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                },
                API_CALL_TIMEOUT_SECONDS_DEFAULT,
                API_CALL_DELAY_SECONDS_DEFAULT,
                API_RETRIES);
    }

    @Override
    public ZenDeskOrganizationItemResponse getOrganization(
            final String authorization,
            final String url,
            final String orgId) {

        if (StringUtils.isBlank(orgId)) {
            throw new IllegalArgumentException("Organization ID is required");
        }

        return localStorage.getOrPutObject(
                ZenDeskClientLive.class.getSimpleName(),
                "ZenDeskAPIOrganizations",
                DigestUtils.sha256Hex(orgId + url),
                ZenDeskOrganizationResponse.class,
                () -> getOrganizationFromApi(authorization, url, orgId)).result().organization();
    }

    private ZenDeskUserResponse getUserFromApi(
            final String authorization,
            final String url,
            final String userId) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".user",
                () -> getUserFromApiLocked(authorization, url, userId));
    }

    /**
     * ZenDesk has API rate limits measured in requests per minute, so we
     * attempt to retry a few times with a delay.
     */
    private ZenDeskUserResponse getUserFromApiLocked(
            final String authorization,
            final String url,
            final String userId) {

        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("User ID is required");
        }

        RATE_LIMITER.acquire();

        final String target = url + "/api/v2/users/" + userId;

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/json")
                        .get(),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(ZenDeskUserResponse.class))
                        .get(),
                e -> new RuntimeException("Failed to get comments from ZenDesk API", e),
                () -> {
                    throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                },
                API_CALL_TIMEOUT_SECONDS_DEFAULT,
                API_CALL_DELAY_SECONDS_DEFAULT,
                API_RETRIES);
    }

    @Override
    public ZenDeskUserItemResponse getUser(
            final String authorization,
            final String url,
            final String userId) {

        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("User ID is required");
        }

        return localStorage.getOrPutObject(
                ZenDeskClientLive.class.getSimpleName(),
                "ZenDeskAPIUsersV2",
                DigestUtils.sha256Hex(userId + url),
                ZenDeskUserResponse.class,
                () -> getUserFromApi(authorization, url, userId)).result().user();
    }
}
