package secondbrain.infrastructure.salesforce;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.test.DirectHttpClientCaller;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.RecordingMutex;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.salesforce.api.*;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SalesforceClientLive.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(Loggers.class)
class SalesforceClientLiveTest {
    private static final String DOMAIN = "acme";
    private static final String QUERY_URL = "https://acme.my.salesforce.com/services/data/v64.0/query";
    private static final long THREE_DAYS = 3 * 24 * 60 * 60;

    private final DirectHttpClientCaller httpCaller = new DirectHttpClientCaller();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final RecordingMutex mutex = new RecordingMutex();
    private final FakeJaxRs http = new FakeJaxRs();

    @Inject
    @Preferred
    private SalesforceClientLive client;

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

    @Produces
    @Identifier("everything")
    DateParser produceDateParser() {
        return ZonedDateTime::parse;
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.salesforce.domain", DOMAIN));
    }

    private static SalesforceEmailRecord email(final String id) {
        return new SalesforceEmailRecord(id, "Subject " + id, "Body " + id, "2024-01-15T00:00:00Z", DOMAIN);
    }

    private static SalesforceEmailQuery emails(final SalesforceEmailRecord... records) {
        return new SalesforceEmailQuery(records.length, true, List.of(records));
    }

    @Test
    void operationsFailFastWhenDomainIsNotConfigured() {
        TestConfigUtil.registerConfig(Map.of());

        assertThrows(IllegalStateException.class, () -> client.getEmails("token", "001", "", ""));
        assertThrows(IllegalStateException.class, () -> client.getTasks("token", "001", "Call", "", ""));
        assertThrows(IllegalStateException.class, () -> client.getOpportunityByAccountId("token", "001"));
        assertThrows(IllegalStateException.class, () -> client.getToken("id", "secret"));
        assertThrows(IllegalStateException.class, () -> client.getAccount("token", "001"));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void getEmailsQueriesEmailMessagesWithDateRangeAndDefaultLimit() {
        http.enqueue(FakeJaxRs.response(200, emails(email("e1"), email("e2"))));

        final SalesforceEmailRecord[] result = client.getEmails("token", "001", "2024-01-02T00:00:00Z", "2024-02-01T00:00:00Z");

        assertArrayEquals(new SalesforceEmailRecord[]{email("e1"), email("e2")}, result);
        assertEquals(List.of(QUERY_URL), http.targets());
        assertEquals(List.of("SELECT Subject,Id,TextBody FROM EmailMessage WHERE RelatedToId='001'"
                        + " AND MessageDate>=2024-01-02T00:00:00Z AND MessageDate<=2024-02-01T00:00:00Z"
                        + " ORDER BY MessageDate DESC, Id DESC Limit 100"),
                http.queryParams("q"));
        assertEquals(List.of("Bearer token"), http.headers("Authorization"));
        assertEquals(List.of(new RecordingLocalStorage.Call(
                        "SalesforceClientLive", "SalesforceAPIEmails", DigestUtils.sha256Hex(DOMAIN + "001"), THREE_DAYS)),
                localStorage.calls);
        assertEquals(List.of("sb_salesforce.lock"), mutex.lockNames);
    }

    @Test
    void getEmailsOmitsBlankAndUnparseableDatesAndHonoursExplicitLimit() {
        http.enqueue(FakeJaxRs.response(200, emails()));

        client.getEmails("token", "001", "not a date", "", 1);

        assertEquals(List.of("SELECT Subject,Id,TextBody FROM EmailMessage WHERE RelatedToId='001' ORDER BY MessageDate DESC, Id DESC Limit 1"),
                http.queryParams("q"));
    }

    @Test
    void getEmailsReturnsEmptyArrayWhenApiFails() {
        http.enqueue(FakeJaxRs.response(500, "server error"));

        assertArrayEquals(new SalesforceEmailRecord[]{}, client.getEmails("token", "001", "", ""));
        assertEquals(1, httpCaller.calls());
    }

    @Test
    void getEmailsDoesNotRetryInvalidResponsesOtherThanRateLimits() {
        httpCaller.failNextWith(() -> new InvalidResponse("forbidden", "", 403));

        assertArrayEquals(new SalesforceEmailRecord[]{}, client.getEmails("token", "001", "", ""));
        assertEquals(1, httpCaller.calls());
    }

    @Test
    void getEmailsBacksOffWhenRateLimited() {
        // The client sleeps for a hard-coded 30 seconds before retrying a 429. A pending interrupt aborts that sleep
        // (vavr rethrows InterruptedException as fatal), which proves the retry branch was taken without waiting.
        httpCaller.failNextWith(() -> {
            Thread.currentThread().interrupt();
            return new InvalidResponse("rate limited", "", 429);
        });

        final Throwable thrown = assertThrows(Throwable.class, () -> client.getEmails("token", "001", "", ""));

        assertInstanceOf(InterruptedException.class, thrown);
        assertEquals(1, httpCaller.calls());
        assertFalse(Thread.interrupted());
    }

    @Test
    void getTasksQueriesTasksByTypeAndActivityDates() {
        final SalesforceTaskRecord task = new SalesforceTaskRecord("t1", "desc", "Call", "Subject", "2024-01-03", DOMAIN);
        http.enqueue(FakeJaxRs.response(200, new SalesforceTaskQuery(1, true, List.of(task))));

        final SalesforceTaskRecord[] result = client.getTasks("token", "001", "Call", "2024-01-01", "2024-01-31");

        assertArrayEquals(new SalesforceTaskRecord[]{task}, result);
        assertEquals(List.of("SELECT Id,Description,Subject,Type,CreatedDate FROM Task WHERE AccountId='001' AND Type='Call'"
                        + " AND ActivityDate>=2024-01-01 AND ActivityDate<=2024-01-31 ORDER BY ActivityDate DESC Limit 100"),
                http.queryParams("q"));
        assertEquals(List.of(new RecordingLocalStorage.Call("SalesforceClientLive", "SalesforceAPITasks",
                DigestUtils.sha256Hex(DOMAIN + "001" + "Call" + "2024-01-01" + "2024-01-31"), THREE_DAYS)), localStorage.calls);
    }

    @Test
    void getTasksWithoutDatesHasNoActivityDateFilter() {
        http.enqueue(FakeJaxRs.response(200, new SalesforceTaskQuery(0, true, List.of())));

        client.getTasks("token", "001", "Email", "", "");

        assertEquals(List.of("SELECT Id,Description,Subject,Type,CreatedDate FROM Task WHERE AccountId='001' AND Type='Email' ORDER BY ActivityDate DESC Limit 100"),
                http.queryParams("q"));
    }

    @Test
    void getTasksReturnsEmptyArrayWhenApiFails() {
        http.enqueue(FakeJaxRs.response(401, "unauthorized"));

        assertArrayEquals(new SalesforceTaskRecord[]{}, client.getTasks("token", "001", "Call", "", ""));
    }

    @Test
    void getOpportunityByAccountIdQueriesOpportunitiesAndUsesDoubleHashedCacheKey() {
        final SalesforceOpportunityQuery opportunities = new SalesforceOpportunityQuery(List.of(Map.of("Id", "006A")));
        http.enqueue(FakeJaxRs.response(200, opportunities));

        assertEquals(opportunities, client.getOpportunityByAccountId("token", "001"));
        assertEquals(List.of("SELECT FIELDS(ALL) FROM Opportunity WHERE AccountId='001' ORDER BY CloseDate DESC Limit 200"),
                http.queryParams("q"));
        assertEquals(DigestUtils.sha256Hex(DigestUtils.sha256Hex(DOMAIN + "001")), localStorage.calls.getFirst().promptHash());
        assertEquals("SalesforceOpportunityByAccountId", localStorage.calls.getFirst().source());
    }

    @Test
    void getOpportunityByAccountIdReturnsEmptyQueryWhenApiFails() {
        http.enqueue(FakeJaxRs.response(500, "error"));

        assertEquals(List.of(), client.getOpportunityByAccountId("token", "001").getRecords());
    }

    @Test
    void getAccountAndOpportunityIdsReturnsAccountFollowedByNonBlankOpportunityIds() {
        localStorage.prime("SalesforceOpportunityByAccountId", new SalesforceOpportunityQuery(List.of(
                Map.of("Id", "006A"),
                Map.of("Id", " "),
                Map.of("Name", "no id"),
                Map.of("Id", "006B"))));

        assertEquals(List.of("001", "006A", "006B"), client.getAccountAndOpportunityIds("token", "001"));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void getTokenPostsClientCredentialsAndCachesForOneHour() {
        final SalesforceOauthTokenResponse token = new SalesforceOauthTokenResponse("access", "https://acme", "id", "Bearer", "1", "sig");
        http.enqueue(FakeJaxRs.response(200, token));

        assertEquals(token, client.getToken("client-id", "client-secret"));
        assertEquals(List.of("https://acme.my.salesforce.com/services/oauth2/token"), http.targets());
        @SuppressWarnings("unchecked") final MultivaluedMap<String, String> body = (MultivaluedMap<String, String>) http.postedEntities().getFirst();
        assertEquals(List.of("client-id"), body.get("client_id"));
        assertEquals(List.of("client-secret"), body.get("client_secret"));
        assertEquals(List.of("client_credentials"), body.get("grant_type"));
        assertEquals(new RecordingLocalStorage.Call("SalesforceClientLive", "SalesforceAPIToken", DigestUtils.sha256Hex(DOMAIN), 3600),
                localStorage.calls.getFirst());
    }

    @Test
    void getTokenPropagatesApiFailures() {
        http.enqueue(FakeJaxRs.response(401, "bad credentials"));

        final ExternalFailure ex = assertThrows(ExternalFailure.class, () -> client.getToken("client-id", "wrong"));

        assertTrue(String.valueOf(ex.getMessage()).startsWith("Failed to call the Salesforce API"));
    }

    @Test
    void anyItemsInDurationIsTrueWhenAnyRelatedRecordHasAnEmail() {
        localStorage.prime("SalesforceOpportunityByAccountId", new SalesforceOpportunityQuery(List.of(Map.of("Id", "006A"))));
        http.enqueue(FakeJaxRs.response(200, emails()))
                .enqueue(FakeJaxRs.response(200, emails(email("e1"))));

        assertTrue(client.anyItemsInDuration("token", "001", ChronoUnit.MONTHS, ChronoUnit.MONTHS));
        // One single-result email query per related id: the account and its opportunity
        assertEquals(2, http.queryParams("q").size());
        assertTrue(http.queryParams("q").get(0).contains("RelatedToId='001'"));
        assertTrue(http.queryParams("q").get(1).contains("RelatedToId='006A'"));
        assertTrue(http.queryParams("q").stream().allMatch(q -> q.endsWith("Limit 1")));
    }

    @Test
    void anyItemsInDurationIsFalseWhenNoRelatedRecordHasEmails() {
        localStorage.prime("SalesforceOpportunityByAccountId", new SalesforceOpportunityQuery(List.of()));
        http.enqueue(FakeJaxRs.response(200, emails()));

        assertFalse(client.anyItemsInDuration("token", "001", ChronoUnit.MONTHS, ChronoUnit.MONTHS));
    }

    @Test
    void getAccountQueriesAccountNameAndOwner() {
        final SalesforceAccountRecord account = new SalesforceAccountRecord("001", "Foodstuffs North Island",
                new SalesforceAccountOwner("Sophia Gazi", "sophia.gazi@octopus.com"),
                "35 Landing Dr", "Mangere", null, null, "NZ", "Grocery Retail", 24000);
        http.enqueue(FakeJaxRs.response(200, new SalesforceAccountQuery(1, true, List.of(account))));

        assertEquals(Optional.of(account), client.getAccount("token", "001"));
        assertEquals(List.of(QUERY_URL), http.targets());
        assertEquals(List.of("SELECT Id,Name,Owner.Name,Owner.Email,BillingStreet,BillingCity,BillingState,BillingPostalCode,BillingCountry,Industry,NumberOfEmployees FROM Account WHERE Id='001'"), http.queryParams("q"));
        assertEquals(List.of("Bearer token"), http.headers("Authorization"));
        assertEquals(List.of(new RecordingLocalStorage.Call(
                        "SalesforceClientLive", "SalesforceAPIAccount", DigestUtils.sha256Hex(DOMAIN + "001"), THREE_DAYS)),
                localStorage.calls);
        assertEquals(List.of("sb_salesforce.lock"), mutex.lockNames);
    }

    @Test
    void getAccountReturnsEmptyWhenNoAccountMatches() {
        http.enqueue(FakeJaxRs.response(200, new SalesforceAccountQuery(0, true, List.of())));

        assertEquals(Optional.empty(), client.getAccount("token", "001"));
    }

    @Test
    void getAccountReturnsEmptyWhenApiFails() {
        http.enqueue(FakeJaxRs.response(500, "error"));

        assertEquals(Optional.empty(), client.getAccount("token", "001"));
    }
}
