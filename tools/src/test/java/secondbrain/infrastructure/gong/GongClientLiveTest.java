package secondbrain.infrastructure.gong;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.httpclient.HttpClientCaller;
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
import secondbrain.infrastructure.gong.api.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GongClientLive.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(Loggers.class)
class GongClientLiveTest {
    private static final String URL = "https://gong.test";
    private static final String FROM = "2024-01-01T00:00:00Z";
    private static final String TO = "2024-02-01T00:00:00Z";
    private static final long TTL = 60L * 60 * 24 * 90;
    private static final String PARENT_SOURCE = "GongAPICallsExtensiveParentV2";
    private static final String PAGE_SOURCE = "GongAPICallsExtensiveV2";
    private static final String TRANSCRIPT_SOURCE = "GongAPICallTranscript";
    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes());

    private final DirectHttpClientCaller httpCaller = new DirectHttpClientCaller();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final RecordingMutex mutex = new RecordingMutex();
    private final FakeJaxRs http = new FakeJaxRs();

    @Inject
    @Preferred
    private GongClientLive client;

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
    HttpClientCaller produceHttpClientCaller() {
        return httpCaller;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(http.client);
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.gong.url", URL));
    }

    private static GongCallExtensive call(final String id, final GongCallExtensiveContext... context) {
        return new GongCallExtensive(new GongCallExtensiveMetadata(id, "https://gong.test/call/" + id, FROM), List.of(context), List.of());
    }

    private static GongCallExtensiveContext salesforce(final String objectType, final String objectId) {
        return new GongCallExtensiveContext("Salesforce", List.of(new GongCallExtensiveContextObject(objectType, objectId, List.of())));
    }

    private static GongCallsExtensive page(final String cursor, final GongCallExtensive... calls) {
        return new GongCallsExtensive(Arrays.asList(calls), new GongRecords(calls.length, calls.length, 0, cursor));
    }

    private static List<String> ids(final List<GongCallExtensive> calls) {
        return calls.stream().map(c -> c.metaData().id()).toList();
    }

    private GongCallExtensiveQuery postedQuery(final int index) {
        return (GongCallExtensiveQuery) http.postedEntities().get(index);
    }

    @Test
    void getCallsExtensiveFollowsTheCursorUntilItIsBlank() {
        http.enqueue(FakeJaxRs.response(200, page("next", call("c1", salesforce("Account", "1")))))
                .enqueue(FakeJaxRs.response(200, page("", call("c2", salesforce("Account", "2")))));

        final List<GongCallExtensive> result = client.getCallsExtensive("", "c1,c2", "user", "pass", FROM, TO);

        assertEquals(List.of("c1", "c2"), ids(result));
        assertEquals(List.of(URL + "/v2/calls/extensive", URL + "/v2/calls/extensive"), http.targets());
        assertEquals(List.of(AUTH, AUTH), http.headers("Authorization"));
        assertEquals("", postedQuery(0).cursor());
        assertEquals("next", postedQuery(1).cursor());
        assertEquals(new GongCallExtensiveQueryFiler(FROM, TO, null, List.of("c1", "c2")), postedQuery(0).filter());
        assertEquals("Extended", postedQuery(0).contentSelector().context());
        assertEquals(List.of("sb_gong.lock"), mutex.lockNames);

        assertEquals(List.of(new RecordingLocalStorage.Call("GongClientLive", PARENT_SOURCE, DigestUtils.sha256Hex(FROM + TO + "c1,c2"), TTL)),
                localStorage.callsFor(PARENT_SOURCE));
        // Every page is cached with a key that includes the cursor
        assertEquals(List.of(DigestUtils.sha256Hex(FROM + TO + "c1,c2"), DigestUtils.sha256Hex(FROM + TO + "c1,c2" + "next")),
                localStorage.callsFor(PAGE_SOURCE).stream().map(RecordingLocalStorage.Call::promptHash).toList());
    }

    @Test
    void getCallsExtensiveDefaultsTheEndDateToTodayAndOmitsBlankFilters() {
        final String today = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).format(ISO_OFFSET_DATE_TIME);
        http.enqueue(FakeJaxRs.response(200, page("", call("c1"))));

        client.getCallsExtensive("", "", "user", "pass", "", null);

        assertEquals(new GongCallExtensiveQueryFiler(null, today, null, null), postedQuery(0).filter());
        assertEquals(DigestUtils.sha256Hex("" + today + ""), localStorage.callsFor(PARENT_SOURCE).getFirst().promptHash());
    }

    @Test
    @SuppressWarnings("NullAway")
    void getCallsExtensiveKeepsOnlyCallsLinkedToTheSalesforceAccount() {
        final GongCallExtensive matching = call("match", salesforce("Account", "acct-1"));
        final GongCallExtensive otherAccount = call("other-account", salesforce("Account", "acct-2"));
        final GongCallExtensive opportunity = call("opportunity", salesforce("Opportunity", "acct-1"));
        final GongCallExtensive otherSystem = call("hubspot", new GongCallExtensiveContext("HubSpot",
                List.of(new GongCallExtensiveContextObject("Account", "acct-1", List.of()))));
        final GongCallExtensive noContext = call("no-context");
        final GongCallExtensive mixed = call("mixed", salesforce("Opportunity", "x"), salesforce("Account", "acct-1"));
        http.enqueue(FakeJaxRs.response(200, page("", matching, otherAccount, opportunity, otherSystem, noContext, null, mixed)));

        final List<GongCallExtensive> result = client.getCallsExtensive("acct-1", null, "user", "pass", FROM, TO);

        assertEquals(List.of("match", "mixed"), ids(result));
    }

    @Test
    @SuppressWarnings("NullAway")
    void getCallsExtensiveWithBlankCompanyKeepsCallsWithAnyContextButDropsNulls() {
        http.enqueue(FakeJaxRs.response(200, page("", call("a", salesforce("Account", "1")), null, call("no-context"))));

        final List<GongCallExtensive> result = client.getCallsExtensive(" ", null, "user", "pass", FROM, TO);

        // A call needs at least one context object to match, even when the company is blank
        assertEquals(List.of("a"), ids(result));
    }

    @Test
    void getCallsExtensiveUsesTheCachedParentResult() {
        localStorage.prime(PARENT_SOURCE, new GongCallExtensive[]{call("cached", salesforce("Account", "1"))});

        assertEquals(List.of("cached"), ids(client.getCallsExtensive("1", null, "user", "pass", FROM, TO)));
        assertTrue(http.targets().isEmpty());
        assertTrue(mutex.lockNames.isEmpty());
    }

    @Test
    void getCallsExtensiveReturnsEmptyListForNullCachedResult() {
        localStorage.primeNull(PARENT_SOURCE);

        assertEquals(List.of(), client.getCallsExtensive("1", null, "user", "pass", FROM, TO));
    }

    @Test
    void getCallsExtensiveStopsWhenAPageIsMissing() {
        localStorage.primeNull(PAGE_SOURCE);

        assertEquals(List.of(), client.getCallsExtensive("", null, "user", "pass", FROM, TO));
        assertEquals(1, localStorage.callsFor(PAGE_SOURCE).size());
    }

    @Test
    void getCallsExtensiveStopsAfterOneHundredPages() {
        // Every page is a cache hit with a cursor, so the loop only ends at the page limit
        localStorage.prime(PAGE_SOURCE, page("more", call("c", salesforce("Account", "1"))));

        final List<GongCallExtensive> result = client.getCallsExtensive("", null, "user", "pass", FROM, TO);

        assertEquals(100, result.size());
        assertEquals(100, localStorage.callsFor(PAGE_SOURCE).size());
        assertTrue(http.targets().isEmpty());
    }

    @Test
    void getCallsExtensiveRefetchesWithoutTheCacheWhenTheCursorHasExpired() {
        http.enqueue(FakeJaxRs.response(500, "the cursor has expired"))
                .enqueue(FakeJaxRs.response(200, page("", call("fresh", salesforce("Account", "1")))));

        final List<GongCallExtensive> result = client.getCallsExtensive("", null, "user", "pass", FROM, TO);

        assertEquals(List.of("fresh"), ids(result));
        assertEquals(2, http.targets().size());
    }

    @Test
    void getCallsExtensiveRethrowsOtherApiFailures() {
        http.enqueue(FakeJaxRs.response(500, "internal error"));

        final GongAPIException ex = assertThrows(GongAPIException.class,
                () -> client.getCallsExtensive("", null, "user", "pass", FROM, TO));

        assertTrue(String.valueOf(ex.getMessage()).startsWith("Failed to get calls from Gong API"), ex.getMessage());
        assertInstanceOf(InvalidResponse.class, ex.getCause());
        assertEquals(1, http.targets().size());
    }

    @Test
    void getCallTranscriptFormatsEachSpeakerTurn() {
        final GongCallExtensive call = new GongCallExtensive(
                new GongCallExtensiveMetadata("call-1", "", FROM),
                List.of(),
                List.of(new GongCallExtensiveParty("p1", "Alice", "s1")));
        http.enqueue(FakeJaxRs.response(200, new GongCallTranscript(List.of(new GongCallTranscriptCollection(List.of(
                new GongCallTranscriptItem("s1", List.of(new GongCallTranscriptItemSentence("Hello"), new GongCallTranscriptItemSentence("there"))),
                new GongCallTranscriptItem("unknown", List.of(new GongCallTranscriptItemSentence("Bye")))))))));

        final String transcript = client.getCallTranscript("user", "pass", call);

        assertEquals("\nAlice:\n\nHello\nthere\nUnknown Speaker:\n\nBye", transcript);
        assertEquals(List.of(URL + "/v2/calls/transcript"), http.targets());
        assertEquals(List.of(AUTH), http.headers("Authorization"));
        assertEquals(List.of("application/json"), http.headers("Accept"));
        assertEquals(new GongCallTranscriptQuery(new GongCallTranscriptQueryFilter(List.of("call-1"))), http.postedEntities().getFirst());
        assertEquals(List.of(new RecordingLocalStorage.Call("GongClientLive", TRANSCRIPT_SOURCE, "call-1", RecordingLocalStorage.NO_TTL)),
                localStorage.calls);
        assertEquals(List.of("sb_gong.lock"), mutex.lockNames);
    }

    @Test
    @SuppressWarnings("NullAway")
    void getCallTranscriptSkipsCallsWithoutAnId() {
        assertEquals("", client.getCallTranscript("user", "pass", new GongCallExtensive(null, List.of(), List.of())));
        assertEquals("", client.getCallTranscript("user", "pass", call(" ")));
        assertTrue(localStorage.calls.isEmpty());
    }

    @Test
    void getCallTranscriptReturnsEmptyStringWhenTheApiFails() {
        http.enqueue(FakeJaxRs.response(404, ""));

        assertEquals("", client.getCallTranscript("user", "pass", call("call-1")));
    }

    @Test
    void getCallTranscriptReturnsEmptyStringForNullCachedTranscript() {
        localStorage.primeNull(TRANSCRIPT_SOURCE);

        assertEquals("", client.getCallTranscript("user", "pass", call("call-1")));
        assertTrue(http.targets().isEmpty());
    }

    @Test
    @SuppressWarnings("NullAway")
    void getCallTranscriptReturnsEmptyStringWhenTranscriptHasNoEntries() {
        localStorage.prime(TRANSCRIPT_SOURCE, new GongCallTranscript(null));

        assertEquals("", client.getCallTranscript("user", "pass", call("call-1")));
    }
}
