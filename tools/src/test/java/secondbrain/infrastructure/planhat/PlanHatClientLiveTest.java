package secondbrain.infrastructure.planhat;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import io.smallrye.common.annotation.Identifier;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.zip.Zipper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.RecordingMutex;
import secondbrain.infrastructure.planhat.api.*;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PlanHatClientLive.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(Loggers.class)
class PlanHatClientLiveTest {
    private static final String URL = "https://api.planhat.example";
    private static final String TOKEN = "token";
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ZonedDateTime START = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime END = ZonedDateTime.of(2024, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final RecordingMutex mutex = new RecordingMutex();
    private final FakeJaxRs http = new FakeJaxRs();
    private final Map<String, String> settings = new HashMap<>();

    @Inject
    @Preferred
    private PlanHatClientLive client;

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
    @Identifier("everything")
    DateParser produceDateParser() {
        return ZonedDateTime::parse;
    }

    @Produces
    Zipper produceZipper() {
        return new Zipper() {
            @Override
            public String compressString(final String data) {
                return data;
            }

            @Override
            public String decompressString(final String compressedData) {
                return compressedData;
            }
        };
    }

    @BeforeEach
    void registerConfig() {
        settings.put("sb.planhat.pagesize", "2");
        settings.put("sb.planhat.maxoffset", "100");
        settings.put("sb.planhat.maxlength", "524288");
        settings.put("sb.planhat.maxresults", "200");
        TestConfigUtil.registerConfig(settings);
    }

    /**
     * Overrides a setting. Must be called before the client is first used, as CDI creates it lazily.
     */
    private void configure(final String key, final String value) {
        settings.put(key, value);
        TestConfigUtil.registerConfig(settings);
    }

    private static <T> T json(final String json, final Class<T> clazz) {
        try {
            return JSON.readValue(json, clazz);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Conversation conversation(final String id, final String date) {
        return new Conversation(id, "description " + id, "snippet " + id, date, "company", "Company", "subject", "call", null);
    }

    private static Conversation[] page(final Conversation... conversations) {
        return conversations;
    }

    private List<String> conversationIds(final List<Conversation> conversations) {
        return conversations.stream().map(Conversation::getId).toList();
    }

    @Test
    void getConversationsPagesBackwardsUntilAPageHasNothingInRange() {
        http.enqueue(FakeJaxRs.response(200, page(conversation("c1", "2024-01-20T00:00:00Z"), conversation("c2", "2024-01-10T00:00:00Z"))))
                .enqueue(FakeJaxRs.response(200, page(conversation("c3", "2024-01-05T00:00:00Z"), conversation("old", "2023-12-25T00:00:00Z"))))
                .enqueue(FakeJaxRs.response(200, page(conversation("older", "2023-12-01T00:00:00Z"))));

        final List<Conversation> result = client.getConversations(http.client, "company-1", URL, TOKEN, START, END, 600);

        assertEquals(List.of("c1", "c2", "c3"), conversationIds(result));
        assertEquals(List.of("0", "2", "4"), http.queryParams("offset"));
        assertEquals(List.of("2", "2", "2"), http.queryParams("limit"));
        assertEquals(List.of("company-1", "company-1", "company-1"), http.queryParams("cId"));
        assertEquals(List.of("Bearer token", "Bearer token", "Bearer token"), http.headers("Authorization"));
        assertEquals(URL + "/conversations", http.targets().getFirst());
        assertEquals(List.of("sb_planhat.lock", "sb_planhat.lock", "sb_planhat.lock"), mutex.lockNames);

        assertEquals(List.of(new RecordingLocalStorage.Call("PlanHatClientLive", "PlanHatAPIConversationParent",
                DigestUtils.sha256Hex("company-1" + URL + START.format(ISO_OFFSET_DATE_TIME)
                        + END.format(ISO_OFFSET_DATE_TIME)), 600)), localStorage.callsFor("PlanHatAPIConversationParent"));
        // Every page is cached individually as well
        assertEquals(3, localStorage.callsFor("PlanHatAPIConversation").size());
    }

    @Test
    void getConversationsStopsOnceMaxResultsIsReached() {
        configure("sb.planhat.maxresults", "3");
        http.enqueue(FakeJaxRs.response(200, page(conversation("c1", "2024-01-20T00:00:00Z"), conversation("c2", "2024-01-19T00:00:00Z"))))
                .enqueue(FakeJaxRs.response(200, page(conversation("c3", "2024-01-18T00:00:00Z"), conversation("c4", "2024-01-17T00:00:00Z"))));

        final List<Conversation> result = client.getConversations(http.client, "company-1", URL, TOKEN, START, END, 600);

        assertEquals(List.of("c1", "c2", "c3", "c4"), conversationIds(result));
        assertEquals(List.of("0", "2"), http.queryParams("offset"));
    }

    @Test
    void getConversationsStopsAtMaxOffset() {
        configure("sb.planhat.maxoffset", "4");
        http.enqueue(FakeJaxRs.response(200, page(conversation("c1", "2024-01-20T00:00:00Z"), conversation("c2", "2024-01-19T00:00:00Z"))))
                .enqueue(FakeJaxRs.response(200, page(conversation("c3", "2024-01-18T00:00:00Z"), conversation("c4", "2024-01-17T00:00:00Z"))));

        assertEquals(4, client.getConversations(http.client, "company-1", URL, TOKEN, START, END, 600).size());
        assertEquals(List.of("0", "2"), http.queryParams("offset"));
    }

    @Test
    void getConversationsExcludesItemsWithoutDatesAndOmitsCompanyFilterWhenBlank() {
        http.enqueue(FakeJaxRs.response(200, page(conversation("dated", "2024-01-20T00:00:00Z"), conversation("undated", ""))))
                .enqueue(FakeJaxRs.response(200, page()));

        final List<Conversation> result = client.getConversations(http.client, "", URL, TOKEN, START, END, 600);

        assertEquals(List.of("dated"), conversationIds(result));
        assertEquals(List.of(), http.queryParams("cId"));
    }

    @Test
    void getConversationsTrimsDescriptionAndSnippetToMaxLength() {
        configure("sb.planhat.maxlength", "5");
        http.enqueue(FakeJaxRs.response(200, page(new Conversation("c1", "abcdefghij", "0123456789", "2024-01-20T00:00:00Z", null, null, null, null, null))))
                .enqueue(FakeJaxRs.response(200, page()));

        final Conversation result = client.getConversations(http.client, "company-1", URL, TOKEN, START, END, 600).getFirst();

        assertEquals("abcde", result.getDescription());
        assertEquals("01234", result.getSnippet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a number", "0", "-3"})
    void invalidPageSizeFallsBackToDefaultOfFive(final String pageSize) {
        configure("sb.planhat.pagesize", pageSize);
        http.enqueue(FakeJaxRs.response(200, page()));

        client.getConversations(http.client, "company-1", URL, TOKEN, START, END, 600);

        assertEquals(List.of("5"), http.queryParams("limit"));
    }

    @Test
    void anyItemsInDurationOnlyReadsTheFirstPage() {
        final ZonedDateTime lastMonth = ZonedDateTime.now().withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).minusDays(10);
        http.enqueue(FakeJaxRs.response(200, page(conversation("c1", lastMonth.toOffsetDateTime().toString()), conversation("c2", lastMonth.toOffsetDateTime().toString()))));

        assertTrue(client.anyItemsInDuration(http.client, "company-1", URL, TOKEN, ChronoUnit.MONTHS, ChronoUnit.MONTHS));

        assertEquals(List.of("0"), http.queryParams("offset"));
        assertEquals("PlanHatAPIConversationParentDuration", localStorage.calls.getFirst().source());
    }

    @Test
    void anyItemsInDurationIsFalseWithoutConversations() {
        http.enqueue(FakeJaxRs.response(200, page()));

        assertFalse(client.anyItemsInDuration(http.client, "company-1", URL, TOKEN, ChronoUnit.MONTHS, ChronoUnit.MONTHS));
    }

    @Test
    void getCompanyEncodesCompanyIdInPath() {
        final Company company = json("{\"_id\":\"acme co\",\"name\":\"Acme\"}", Company.class);
        http.enqueue(FakeJaxRs.response(200, company));

        assertEquals(company, client.getCompany(http.client, "acme co", URL, TOKEN, 60));
        assertEquals(List.of(URL + "/companies/acme+co"), http.targets());
        assertEquals(new RecordingLocalStorage.Call("PlanHatClientLive", "PlanHatAPICompanyV2", DigestUtils.sha256Hex("acme co" + URL), 60),
                localStorage.calls.getFirst());
    }

    @Test
    void getCompanyPropagatesMissingCompany() {
        http.enqueue(FakeJaxRs.response(404, "missing"));

        assertThrows(secondbrain.domain.exceptions.MissingResponse.class, () -> client.getCompany(http.client, "nope", URL, TOKEN, 60));
    }

    @Test
    void getConversationEmailsTrimsContentAndSnippet() {
        configure("sb.planhat.maxlength", "4");
        final Email[] emails = {json("{\"_id\":\"e1\",\"content\":\"<p>long html</p>\",\"snippet\":\"snippet\"}", Email.class)};
        http.enqueue(FakeJaxRs.response(200, emails));

        final List<Email> result = client.getConversationEmails(http.client, "conv/1", URL, TOKEN, 60);

        assertEquals(1, result.size());
        assertEquals("<p>l", result.getFirst().getContent());
        assertEquals("snip", result.getFirst().getSnippet());
        assertEquals(List.of(URL + "/emails/conversation/conv%2F1"), http.targets());
    }

    @Test
    void getConversationEmailsReturnsEmptyListOnFailure() {
        http.enqueue(FakeJaxRs.response(500, "error"));

        assertEquals(List.of(), client.getConversationEmails(http.client, "conv1", URL, TOKEN, 60));
    }

    @Test
    void getEmailReadsAndTrimsSingleEmail() {
        configure("sb.planhat.maxlength", "3");
        http.enqueue(FakeJaxRs.response(200, json("{\"_id\":\"e1\",\"content\":\"abcdef\",\"snippet\":\"xyz123\"}", Email.class)));

        final Email email = client.getEmail(http.client, "e1", URL, TOKEN, 60);

        assertEquals("abc", email.getContent());
        assertEquals("xyz", email.getSnippet());
        assertEquals(List.of(URL + "/emails/e1"), http.targets());
        assertEquals("PlanHatAPIEmail", localStorage.calls.getFirst().source());
    }

    @Test
    void getTicketPartsTrimsBody() {
        configure("sb.planhat.maxlength", "2");
        http.enqueue(FakeJaxRs.response(200, new TicketPart[]{json("{\"_id\":\"p1\",\"body\":\"hello\"}", TicketPart.class)}));

        final List<TicketPart> parts = client.getTicketParts(http.client, "t 1", URL, TOKEN, 60);

        assertEquals("he", parts.getFirst().getBody());
        assertEquals(List.of(URL + "/tickets/t+1/parts"), http.targets());
    }

    @Test
    void getTicketPartsReturnsEmptyListOnFailure() {
        http.enqueue(FakeJaxRs.response(503, "error"));

        assertEquals(List.of(), client.getTicketParts(http.client, "t1", URL, TOKEN, 60));
    }

    @Test
    void getObjectivesFiltersByCompanyId() {
        final Objective objective = json("{\"_id\":\"o1\",\"companyId\":\"co1\",\"companyName\":\"Acme\"}", Objective.class);
        http.enqueue(FakeJaxRs.response(200, new Objective[]{objective}));

        assertEquals(List.of(objective), client.getObjectives(http.client, "co1", URL, TOKEN, 60));
        assertEquals(List.of(URL + "/objectives"), http.targets());
        assertEquals(List.of("co1"), http.queryParams("companyId"));
    }

    @Test
    void getObjectivesReturnsEmptyListOnFailure() {
        http.enqueue(FakeJaxRs.response(401, "error"));

        assertEquals(List.of(), client.getObjectives(http.client, "co1", URL, TOKEN, 60));
    }

    @Test
    void getOpportunitiesFiltersByCompanyId() {
        final Opportunity opportunity = json("{\"_id\":\"op1\",\"status\":\"open\",\"companyId\":\"co1\"}", Opportunity.class);
        http.enqueue(FakeJaxRs.response(200, new Opportunity[]{opportunity}));

        assertEquals(List.of(opportunity), client.getOpportunities(http.client, "co1", URL, TOKEN, 60));
        assertEquals(List.of(URL + "/opportunities"), http.targets());
        assertEquals(List.of("co1"), http.queryParams("companyId"));
        assertEquals("PlanHatAPIOpportunities", localStorage.calls.getFirst().source());
    }

    @Test
    void getOpportunitiesReturnsEmptyListOnFailure() {
        http.enqueue(FakeJaxRs.response(500, "error"));

        assertEquals(List.of(), client.getOpportunities(http.client, "co1", URL, TOKEN, 60));
    }

    @Test
    void getUserReadsUserById() {
        final PlanHatUser user = json("{\"_id\":\"u1\",\"firstName\":\"Jane\",\"lastName\":\"Doe\"}", PlanHatUser.class);
        http.enqueue(FakeJaxRs.response(200, user));

        assertEquals(user, client.getUser(http.client, "u1", URL, TOKEN, 60));
        assertEquals(List.of(URL + "/users/u1"), http.targets());
        assertEquals(new RecordingLocalStorage.Call("PlanHatClientLive", "PlanHatAPIUser", DigestUtils.sha256Hex("u1" + URL), 60),
                localStorage.calls.getFirst());
    }
}
