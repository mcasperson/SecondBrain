package secondbrain.domain.tools.zendesk;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.debug.DebugToolArgsKeyValue;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.processing.MockRatingMetadata;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.processing.RatingToolRatingMetadata;
import secondbrain.domain.processing.SentenceVectorizerDataToRagDoc;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.StubSentenceVectorizer;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.api.*;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ZenDeskIndividualTicket.class)
@AddBeanClasses(ZenDeskTicketConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(DebugToolArgsKeyValue.class)
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(Loggers.class)
// The keywords tool is replaced by a mock from a producer method
@ExcludeBeanClasses(Keywords.class)
class ZenDeskIndividualTicketTest {
    private static final String URL = "https://acme.zendesk.test";
    private static final String PROMPT = "Summarise the ticket";
    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("agent@acme.test/token:tok".getBytes(StandardCharsets.UTF_8));

    private final FakeZenDeskClient zenDeskClient = new FakeZenDeskClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final MockRatingMetadata ratingMetadata = new MockRatingMetadata();

    @Inject
    private ZenDeskIndividualTicket tool;

    @Produces
    @Preferred
    ZenDeskClient produceZenDeskClient() {
        return zenDeskClient;
    }

    @Produces
    @Preferred
    RatingMetadata produceRatingMetadata() {
        return ratingMetadata;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    @Identifier("removeSpacing")
    SanitizeDocument produceRemoveSpacing() {
        return new SanitizeDocument() {
            @Override
            public @Nullable String sanitize(@Nullable final String document) {
                return document;
            }

            @Override
            public @Nullable String sanitize(@Nullable final String document, final boolean unsanitize) {
                return document;
            }
        };
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return new StubSentenceVectorizer();
    }

    @Produces
    Keywords produceKeywords() {
        return mock(Keywords.class);
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("zendesk-ticket", 19);
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
        zenDeskClient.users.put("1", new ZenDeskUserItemResponse("Alice", "1", "alice@acme.test", 10L));
        zenDeskClient.users.put("7", new ZenDeskUserItemResponse("Bob", "7", "bob@acme.test", 10L));
        zenDeskClient.organizations.put("10", new ZenDeskOrganizationItemResponse("Acme", "10"));
        zenDeskClient.comments = new ZenDeskCommentsResponse(List.of(
                new ZenDeskCommentResponse("I cannot log in.\n\nPlease help.", 1L, "2024-01-01"),
                new ZenDeskCommentResponse("Have you tried resetting?", 99L, "2024-01-02")));
    }

    private static Map<String, String> env() {
        return Map.of(
                "zendesk_user", "agent@acme.test",
                ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG, "enc(tok)",
                "zendesk_url", URL,
                ZenDeskIndividualTicket.ZENDESK_ENTITY_NAME_CONTEXT_ARG, "AcmeCorp");
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>(List.of(
                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG, "123", true),
                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TICKET_SUBJECT_ARG, "Login broken", true),
                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TICKET_ORGANIZATION_ARG, "10", true),
                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TICKET_ASSIGNEE_ARG, "7", true),
                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TICKET_CREATED_AT_ARG, "2024-01-01T00:00:00Z", true)));
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    @Test
    void describesItself() {
        assertEquals("ZenDeskIndividualTicket", tool.getName());
        assertEquals("ZenDesk Ticket", tool.getContextLabel());
        assertEquals("Returns the details of a single ZenDesk support ticket", tool.getDescription());
        assertEquals(List.of("ticketId"), tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextBuildsTheTicketFromItsComments() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(1, context.size());
        final RagDocumentContext<Void> ticket = context.getFirst();
        final String expectedDocument = """
                Login broken
                From Alice (alice@acme.test) at Acme:
                I cannot log in.
                Please help.
                From Unknown User (Unknown Email) at Unknown Organization:
                Have you tried resetting?""";
        assertEquals(expectedDocument, ticket.document());
        assertEquals("123", ticket.id());
        assertEquals("ZenDeskIndividualTicket", ticket.tool());
        assertEquals("ZenDesk Ticket 2024-01-01T00:00:00Z Acme", ticket.contextLabel());
        assertEquals("[ZenDesk Ticket 123](Login broken - Acme - Bob [123](" + URL + "/agent/tickets/123))", ticket.link());
        assertEquals(List.of(new IntermediateResult(expectedDocument, "Data-ZenDesk-123-AcmeCorp.txt")), ticket.getIntermediateResults());
        assertEquals(List.of("comments:" + AUTH + ":" + URL + ":123:" + (60 * 60 * 24 * 90)), zenDeskClient.commentCalls);
    }

    @Test
    void getContextLimitsTheNumberOfComments() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(ZenDeskOrganization.NUM_COMMENTS_ARG, "1"));

        assertFalse(context.getFirst().document().contains("resetting"));
        assertTrue(context.getFirst().document().contains("Please help."));
    }

    @Test
    void getContextUsesTheHistoryTtl() {
        tool.getContext(env(), List.of(PROMPT), args(ZenDeskIndividualTicket.ZENDESK_HISTORY_TTL_ARG, "60"));

        assertEquals(List.of("comments:" + AUTH + ":" + URL + ":123:60"), zenDeskClient.commentCalls);
    }

    @Test
    void getContextFallsBackWhenTheOrganizationAndAssigneeAreUnknown() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), List.of(
                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG, "123", true),
                new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TICKET_SUBJECT_ARG, "Login\r\nbroken\nagain", true)));

        final RagDocumentContext<Void> ticket = context.getFirst();
        // Without a created date or organization, the label is just the tool label
        assertEquals("ZenDesk Ticket", ticket.contextLabel());
        // Line breaks are removed from the subject in the link
        assertEquals("[ZenDesk Ticket 123](Login broken again - Unknown Organization - Unknown User [123](" + URL + "/agent/tickets/123))", ticket.link());
    }

    @Test
    void getContextAddsRatingMetadata() {
        ratingMetadata.setMockRating(4);

        final RagDocumentContext<Void> ticket = tool.getContext(env(), List.of(PROMPT), args()).getFirst();

        assertEquals(4, ticket.getMetadata().getIntValueByName(RatingToolRatingMetadata.FILTER_RATING_META, -1));
    }

    @Test
    void getContextTrimsTheTicketToKeywords() {
        final RagDocumentContext<Void> ticket = tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.KEYWORDS_ARG, "resetting",
                CommonArguments.KEYWORD_WINDOW_ARG, "30")).getFirst();

        assertTrue(ticket.document().contains("resetting"));
        assertFalse(ticket.document().contains("Login broken"));
        assertEquals(List.of("resetting"), ticket.keywordMatches());
    }

    @Test
    void getContextReturnsNothingWhenNoKeywordMatches() {
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(CommonArguments.KEYWORDS_ARG, "invoice")));
    }

    @Test
    void getContextReturnsNothingForABlankTicket() {
        zenDeskClient.comments = new ZenDeskCommentsResponse(List.of());

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), List.of(new ToolArgs(ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG, "123", true))));
    }

    @Test
    void getContextReturnsNothingWithoutCredentials() {
        assertEquals(List.of(), tool.getContext(Map.of("zendesk_url", URL), List.of(PROMPT), args()));
        assertTrue(zenDeskClient.commentCalls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheCommentsCannotBeLoaded() {
        zenDeskClient.failComments = true;

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
    }

    @Test
    void callSendsTheTicketToTheLlm() {
        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args());

        assertEquals("answer", result.getResponse());
        final RagMultiDocumentContext<?> request = llmClient.requests.getFirst().document();
        assertEquals(List.of(PROMPT), request.getPrompts());
        assertEquals("You will be penalized for including ticket numbers or IDs, invoice numbers, purchase order numbers, or reference numbers.",
                request.instructions());
        assertEquals(List.of("123"), request.getIndividualContexts().stream().map(RagDocumentContext::id).toList());
        assertEquals("ZenDeskIndividualTicket", llmClient.requests.getFirst().tool());
    }

    @Test
    void callFailsWhenThereIsNoTicketContext() {
        assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args(CommonArguments.KEYWORDS_ARG, "invoice")));
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 19 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }

    /**
     * Serves users, organizations and comments from in-memory collections. Unknown users and organizations throw, as
     * the live client does for a 404.
     */
    private static final class FakeZenDeskClient implements ZenDeskClient {
        final Map<String, ZenDeskUserItemResponse> users = new HashMap<>();
        final Map<String, ZenDeskOrganizationItemResponse> organizations = new HashMap<>();
        final List<String> commentCalls = new CopyOnWriteArrayList<>();
        ZenDeskCommentsResponse comments = new ZenDeskCommentsResponse(List.of());
        boolean failComments;

        @Override
        public boolean anyItemsInDuration(final String authorization, final String url, final String organization, final ChronoUnit duration, final ChronoUnit cached) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ZenDeskTicket> getTickets(final String authorization, final String url, final String query, final int ttlSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ZenDeskTicket getTicket(final String authorization, final String url, final String ticketId, final int ttlSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ZenDeskCommentsResponse getComments(final String authorization, final String url, final String ticketId, final int ttlSeconds) {
            commentCalls.add("comments:" + authorization + ":" + url + ":" + ticketId + ":" + ttlSeconds);
            if (failComments) {
                throw new IllegalStateException("ZenDesk unavailable");
            }
            return comments;
        }

        @Override
        public ZenDeskOrganizationItemResponse getOrganization(final String authorization, final String url, final String orgId) {
            return Optional.ofNullable(organizations.get(orgId)).orElseThrow(() -> new IllegalStateException("No organization " + orgId));
        }

        @Override
        public ZenDeskUserItemResponse getUser(final String authorization, final String url, final String userId) {
            return Optional.ofNullable(users.get(userId)).orElseThrow(() -> new IllegalStateException("No user " + userId));
        }
    }
}
