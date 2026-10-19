package secondbrain.infrastructure.zendesk;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.web.ClientConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.test.DirectHttpClientCaller;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.RecordingMutex;
import secondbrain.infrastructure.zendesk.api.*;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ZenDeskClientLive}.
 * Note: the client rate limits itself to one API call per second, so each test keeps the number of calls small.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ZenDeskClientLive.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(Loggers.class)
class ZenDeskClientLiveTest {
    private static final String URL = "https://acme.zendesk.com";
    private static final String AUTH = "Basic abc";

    private final DirectHttpClientCaller httpCaller = new DirectHttpClientCaller();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final RecordingMutex mutex = new RecordingMutex();
    private final FakeJaxRs http = new FakeJaxRs();

    @Inject
    @Preferred
    private ZenDeskClientLive client;

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    @Preferred
    Mutex produceMutex() {
        return mutex;
    }

    @Produces
    TimeoutHttpClientCaller produceHttpClientCaller() {
        return httpCaller;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(http.client);
    }

    @SuppressWarnings("NullAway") // a null next_page marks the last page of results
    private static ZenDeskResponse page(@Nullable final String nextPage, final ZenDeskTicket... tickets) {
        return new ZenDeskResponse(List.of(tickets), nextPage);
    }

    /**
     * Ids are unique per test because the client keeps a static in-memory cache of users and organizations.
     */
    private static String uniqueId() {
        return UUID.randomUUID().toString();
    }

    @Test
    void getTicketsRequiresQuery() {
        assertThrows(IllegalArgumentException.class, () -> client.getTickets(AUTH, URL, " ", 60));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void getTicketsFollowsPagesUntilThereIsNoNextPage() {
        http.enqueue(FakeJaxRs.response(200, page("https://next", new ZenDeskTicket("1", "first"), new ZenDeskTicket("2", "second"))))
                .enqueue(FakeJaxRs.response(200, page(null, new ZenDeskTicket("3", "third"))));

        final List<ZenDeskTicket> tickets = client.getTickets(AUTH, URL, "type:ticket organization:42", 300);

        assertEquals(List.of("1", "2", "3"), tickets.stream().map(ZenDeskTicket::id).toList());
        assertEquals(List.of(URL + "/api/v2/search.json", URL + "/api/v2/search.json"), http.targets());
        assertEquals(List.of("1", "2"), http.queryParams("page"));
        assertEquals(List.of("type:ticket organization:42", "type:ticket organization:42"), http.queryParams("query"));
        assertEquals(List.of("created_at", "created_at"), http.queryParams("sort_by"));
        assertEquals(List.of("desc", "desc"), http.queryParams("sort_order"));
        assertEquals(List.of(AUTH, AUTH), http.headers("Authorization"));
        assertEquals(List.of(new RecordingLocalStorage.Call("ZenDeskClientLive", "ZenDeskApiTicketsV2",
                DigestUtils.sha256Hex(URL + "type:ticket organization:42" + 10), 300)), localStorage.calls);
        assertEquals(List.of("sb_zendesk.lock"), mutex.lockNames);
    }

    @Test
    void getTicketsReturnsEmptyListWhenCacheHoldsNull() {
        localStorage.primeNull("ZenDeskApiTicketsV2");

        assertEquals(List.of(), client.getTickets(AUTH, URL, "type:ticket", 60));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void getTicketsPropagatesApiFailures() {
        http.enqueue(FakeJaxRs.response(500, "error"));

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> client.getTickets(AUTH, URL, "type:ticket", 60));

        assertEquals("Failed to get comments from ZenDesk API", ex.getMessage());
    }

    @Test
    void getTicketReadsSingleTicketAndCachesByUrlAndId() {
        final ZenDeskTicket ticket = new ZenDeskTicket("77", "Printer on fire");
        http.enqueue(FakeJaxRs.response(200, new ZenDeskTicketResponse(ticket)));

        assertEquals(ticket, client.getTicket(AUTH, URL, "77", 120));
        assertEquals(List.of(URL + "/api/v2/tickets/77.json"), http.targets());
        assertEquals(List.of(new RecordingLocalStorage.Call("ZenDeskClientLive", "ZenDeskApiTicket", DigestUtils.sha256Hex(URL + "77"), 120)),
                localStorage.calls);
    }

    @Test
    void getTicketRequiresId() {
        assertThrows(IllegalArgumentException.class, () -> client.getTicket(AUTH, URL, "", 120));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void getCommentsReadsCommentsWithoutExpiry() {
        final ZenDeskCommentsResponse comments = new ZenDeskCommentsResponse(List.of());
        http.enqueue(FakeJaxRs.response(200, comments));

        assertEquals(comments, client.getComments(AUTH, URL, "77", 999));
        assertEquals(List.of(URL + "/api/v2/tickets/77/comments"), http.targets());
        // The requested TTL is ignored: comments are cached with a TTL of 0
        assertEquals(List.of(new RecordingLocalStorage.Call("ZenDeskClientLive", "ZenDeskApiCommentsV2", DigestUtils.sha256Hex("77" + URL), 0)),
                localStorage.calls);
    }

    @Test
    void getCommentsRequiresTicketId() {
        assertThrows(IllegalArgumentException.class, () -> client.getComments(AUTH, URL, " ", 0));
    }

    @Test
    void getOrganizationIsServedFromMemoryAfterFirstLookup() {
        final String orgId = uniqueId();
        final ZenDeskOrganizationItemResponse org = new ZenDeskOrganizationItemResponse("Acme", orgId);
        http.enqueue(FakeJaxRs.response(200, new ZenDeskOrganizationResponse(org)));

        assertEquals(org, client.getOrganization(AUTH, URL, orgId));
        assertEquals(org, client.getOrganization(AUTH, URL, orgId));

        assertEquals(List.of(URL + "/api/v2/organizations/" + orgId), http.targets());
        assertEquals(1, localStorage.calls.size());
        assertEquals("ZenDeskAPIOrganizations", localStorage.calls.getFirst().source());
    }

    @Test
    void getOrganizationRequiresId() {
        assertThrows(IllegalArgumentException.class, () -> client.getOrganization(AUTH, URL, ""));
    }

    @Test
    void getUserIsServedFromMemoryAfterFirstLookup() {
        final String userId = uniqueId();
        final ZenDeskUserItemResponse user = new ZenDeskUserItemResponse("Jane", userId, "jane@example.org", 5L);
        http.enqueue(FakeJaxRs.response(200, new ZenDeskUserResponse(user)));

        assertEquals(user, client.getUser(AUTH, URL, userId));
        assertEquals(user, client.getUser(AUTH, URL, userId));

        assertEquals(List.of(URL + "/api/v2/users/" + userId), http.targets());
        assertEquals(List.of(new RecordingLocalStorage.Call("ZenDeskClientLive", "ZenDeskAPIUsersV2",
                DigestUtils.sha256Hex(userId + URL), RecordingLocalStorage.NO_TTL)), localStorage.calls);
    }

    @Test
    void getUserPropagatesMissingUser() {
        http.enqueue(FakeJaxRs.response(404, "not found"));

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> client.getUser(AUTH, URL, uniqueId()));

        assertInstanceOf(MissingResponse.class, ex.getCause());
    }

    @Test
    void getUserRequiresId() {
        assertThrows(IllegalArgumentException.class, () -> client.getUser(AUTH, URL, " "));
    }

    @Test
    void anyItemsInDurationForOrganizationReadsOnlyOnePage() {
        http.enqueue(FakeJaxRs.response(200, page("https://next", new ZenDeskTicket("1", "first"))));

        assertTrue(client.anyItemsInDuration(AUTH, URL, "42", ChronoUnit.MONTHS, ChronoUnit.MONTHS));

        assertEquals(1, httpCaller.calls());
        final String query = http.queryParams("query").getFirst();
        assertTrue(query.startsWith("type:ticket created>"), query);
        assertTrue(query.contains(" created<"), query);
        assertTrue(query.endsWith(" organization:42"), query);
        assertEquals("ZenDeskApiTicketsDurationV2", localStorage.calls.getFirst().source());
        assertEquals(ChronoUnit.MONTHS.getDuration().toSeconds(), localStorage.calls.getFirst().ttlSeconds());
    }

    @Test
    void anyItemsInDurationWithoutOrganizationIsFalseWhenNoTicketsExist() {
        http.enqueue(FakeJaxRs.response(200, page(null)));

        assertFalse(client.anyItemsInDuration(AUTH, URL, "", ChronoUnit.MONTHS, ChronoUnit.MONTHS));
        assertFalse(http.queryParams("query").getFirst().contains("organization:"));
    }
}
