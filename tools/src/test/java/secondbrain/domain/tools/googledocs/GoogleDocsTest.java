package secondbrain.domain.tools.googledocs;

import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.docs.v1.model.AutoText;
import com.google.api.services.docs.v1.model.Body;
import com.google.api.services.docs.v1.model.Document;
import com.google.api.services.docs.v1.model.Paragraph;
import com.google.api.services.docs.v1.model.ParagraphElement;
import com.google.api.services.docs.v1.model.Person;
import com.google.api.services.docs.v1.model.RichLink;
import com.google.api.services.docs.v1.model.StructuralElement;
import com.google.api.services.docs.v1.model.Table;
import com.google.api.services.docs.v1.model.TextRun;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.HashMapEnvironmentSettings;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.hooks.PostInferenceHook;
import secondbrain.domain.hooks.PreProcessingHook;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.processing.SentenceVectorizerDataToRagDoc;
import secondbrain.domain.test.FakeGoogleDocsClient;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.StubSentenceVectorizer;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.test.TestHooks;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.googledocs.GoogleDocsClient;
import secondbrain.infrastructure.llm.LlmClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link GoogleDocs}. Documents are downloaded from a {@link FakeGoogleDocsClient} instead of the Google
 * Docs API, and the rating tool is a mock, so no network or LLM access is needed. Every test supplies credentials,
 * as falling back to the application default credentials may contact the GCE metadata server.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GoogleDocs.class)
@AddBeanClasses(GoogleDocsConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(Loggers.class)
// The rating and keyword tools are replaced by mocks from producer methods
@ExcludeBeanClasses({RatingTool.class, Keywords.class})
class GoogleDocsTest {
    private static final String PROMPT = "What does the design document say?";
    private static final String TOOL = "GoogleDocs";
    private static final String DOC_ID = "doc-1";
    private static final String SUMMARY_INSTRUCTIONS = "You are a helpful agent";
    private static final String SERVICE_ACCOUNT_EMAIL = "reader@test-project.iam.gserviceaccount.com";

    private static String serviceAccountJson = "";

    private final FakeGoogleDocsClient googleDocsClient = new FakeGoogleDocsClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient()
            .respondWith(doc -> SUMMARY_INSTRUCTIONS.equals(doc.instructions()) ? "Short summary" : "answer");
    private final RatingTool ratingTool = mock(RatingTool.class);
    private final AtomicReference<String> rating = new AtomicReference<>("10");
    private final AtomicBoolean ratingFails = new AtomicBoolean();
    private final List<Map<String, String>> ratingEnvironments = new CopyOnWriteArrayList<>();
    private final List<List<String>> ratingPrompts = new CopyOnWriteArrayList<>();

    @Inject
    private GoogleDocs tool;

    @Produces
    GoogleDocsClient produceGoogleDocsClient() {
        return googleDocsClient;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    RatingTool produceRatingTool() {
        return ratingTool;
    }

    @Produces
    Keywords produceKeywords() {
        return mock(Keywords.class);
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return new StubSentenceVectorizer();
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("google-docs", 37);
    }

    @Produces
    PreProcessingHook produceAppendHook() {
        return TestHooks.appendDocument("append", "Hook label", "hook document");
    }

    @Produces
    PreProcessingHook produceUpperCaseHook() {
        return TestHooks.upperCase("upper");
    }

    @Produces
    PostInferenceHook producePostHook() {
        return TestHooks.appendResponse("post", " [post]");
    }

    @BeforeAll
    static void createServiceAccount() throws NoSuchAlgorithmException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final String key = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        serviceAccountJson = """
                {"type": "service_account", "project_id": "test-project", "private_key_id": "key-1",
                "private_key": "-----BEGIN PRIVATE KEY-----\\n%s\\n-----END PRIVATE KEY-----\\n",
                "client_email": "%s", "client_id": "123", "token_uri": "https://oauth2.googleapis.com/token"}
                """.formatted(key.replace("\n", "\\n"), SERVICE_ACCOUNT_EMAIL);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
        googleDocsClient.addDocument(DOC_ID, document(paragraph(text("The service uses a queue.\n"))));
        when(ratingTool.call(anyMap(), anyList(), anyList())).thenAnswer(invocation -> {
            ratingEnvironments.add(Map.copyOf(invocation.<Map<String, String>>getArgument(0)));
            ratingPrompts.add(invocation.getArgument(1));
            if (ratingFails.get()) {
                throw new IllegalStateException("Rating failed");
            }
            return new RagMultiDocumentContext<Void>("rating").updateResponse(rating.get());
        });
    }

    private static Map<String, String> env() {
        return Map.of("google_access_token", "enc(token-1)", "google_access_token_expires", "enc(1700000000)");
    }

    private static List<ToolArgs> args(final String... extraNameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(GoogleDocs.GOOGLE_DOC_ID_ARG, DOC_ID, true));
        for (int i = 0; i < extraNameValues.length; i += 2) {
            arguments.add(new ToolArgs(extraNameValues[i], extraNameValues[i + 1], true));
        }
        return arguments;
    }

    private static Document document(final StructuralElement... content) {
        return new Document().setBody(new Body().setContent(List.of(content)));
    }

    private static StructuralElement paragraph(final ParagraphElement... elements) {
        return new StructuralElement().setParagraph(new Paragraph().setElements(List.of(elements)));
    }

    private static ParagraphElement text(final String content) {
        return new ParagraphElement().setTextRun(new TextRun().setContent(content));
    }

    private static ParagraphElement person(final String personId) {
        return new ParagraphElement().setPerson(new Person().setPersonId(personId));
    }

    private static ParagraphElement richLink(final String richLinkId) {
        return new ParagraphElement().setRichLink(new RichLink().setRichLinkId(richLinkId));
    }

    private FakeGoogleDocsClient.Call lastCall() {
        return googleDocsClient.getCalls().getLast();
    }

    private GoogleCredentials credentials() {
        final HttpCredentialsAdapter adapter = assertInstanceOf(HttpCredentialsAdapter.class, lastCall().credentials());
        return assertInstanceOf(GoogleCredentials.class, adapter.getCredentials());
    }

    private List<RagDocumentContext<Void>> getContext(final Map<String, String> env, final List<ToolArgs> arguments) {
        return tool.getContext(env, List.of(PROMPT), arguments);
    }

    private static List<String> documents(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::document).toList();
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals("Google Document", tool.getContextLabel());
        assertEquals("Queries a Google Docs document", tool.getDescription());
        assertEquals(List.of(GoogleDocs.GOOGLE_DOC_ID_ARG, CommonArguments.KEYWORDS_ARG, CommonArguments.KEYWORD_WINDOW_ARG,
                        CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, CommonArguments.SUMMARIZE_DOCUMENT_ARG,
                        CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, CommonArguments.CONTENT_RATING_QUESTION_ARG,
                        CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, CommonArguments.DEFAULT_RATING_ARG,
                        CommonArguments.PREINITIALIZATION_HOOKS_ARG, CommonArguments.PREPROCESSOR_HOOKS_ARG,
                        CommonArguments.POSTINFERENCE_HOOKS_ARG),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextReturnsTheDocument() {
        final List<RagDocumentContext<Void>> context = getContext(env(), args());

        assertEquals(1, context.size());
        final RagDocumentContext<Void> document = context.getFirst();
        assertEquals("The service uses a queue.\n", document.document());
        assertEquals(DOC_ID, document.id());
        assertEquals(TOOL, document.tool());
        assertEquals("Google Document", document.contextLabel());
        assertEquals("[Google Doc doc-1](https://docs.google.com/document/d/doc-1)", document.link());
        // Without a filter question the document is not rated
        assertEquals(List.of(), document.getMetadata());
        assertTrue(ratingPrompts.isEmpty());
        assertEquals(DOC_ID, lastCall().documentId());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextGroupsParagraphElementsByType() {
        googleDocsClient.addDocument(DOC_ID, document(
                paragraph(text("Title\n")),
                paragraph(text("Owned by "), person("person-1"), text(" and "), richLink("link-1")),
                new StructuralElement().setTable(new Table()),
                paragraph(text(""), text("End\n"))));

        final List<RagDocumentContext<Void>> context = getContext(env(), args());

        // Within a paragraph people come first, then text, then auto text, then rich links, each separated by a new line.
        // Paragraphs are joined without a separator, empty text is dropped, and tables are ignored.
        assertEquals(List.of("Title\nperson-1\nOwned by \n and \nlink-1End\n"), documents(context));
    }

    @Test
    void getContextIncludesAutoTextAsJson() {
        final AutoText autoText = new AutoText().setType("PAGE_NUMBER");
        autoText.setFactory(GsonFactory.getDefaultInstance());
        googleDocsClient.addDocument(DOC_ID, document(paragraph(text("Page "), new ParagraphElement().setAutoText(autoText))));

        final String document = getContext(env(), args()).getFirst().document();

        assertTrue(document.startsWith("Page \n{"), document);
        assertTrue(document.contains("\"type\"") && document.contains("\"PAGE_NUMBER\""), document);
    }

    @Test
    void getContextUsesTheEncryptedAccessTokenAndExpiry() {
        getContext(env(), args());

        final AccessToken accessToken = Objects.requireNonNull(credentials().getAccessToken());
        assertEquals("token-1", accessToken.getTokenValue());
        assertEquals(1700000000L * 1000L, accessToken.getExpirationTime().getTime());
    }

    @Test
    void getContextUsesAPlainTextAccessTokenAndExpiry() {
        getContext(Map.of("google_access_token", "plain-token", "google_access_token_expires", "1800000000"), args());

        final AccessToken accessToken = Objects.requireNonNull(credentials().getAccessToken());
        assertEquals("plain-token", accessToken.getTokenValue());
        assertEquals(1800000000L * 1000L, accessToken.getExpirationTime().getTime());
    }

    @ParameterizedTest
    @CsvSource({"soon", "''"})
    void getContextDefaultsTheExpiryToAnHourFromNow(final String expires) {
        final Map<String, String> env = new HashMap<>();
        env.put("google_access_token", "plain-token");
        if (!expires.isEmpty()) {
            env.put("google_access_token_expires", expires);
        }
        // The default treats the local time as UTC
        final long before = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(3600).toEpochSecond(ZoneOffset.UTC);

        getContext(env, args());

        final long after = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(3600).toEpochSecond(ZoneOffset.UTC);
        final long expiry = Objects.requireNonNull(credentials().getAccessToken()).getExpirationTime().getTime() / 1000L;
        assertTrue(expiry >= before && expiry <= after, () -> "unexpected expiry " + expiry);
    }

    @Test
    void getContextUsesTheServiceAccountFromTheContextWithoutAnAccessToken() {
        getContext(Map.of("google_service_account_json", serviceAccountJson), args());

        final ServiceAccountCredentials credentials = assertInstanceOf(ServiceAccountCredentials.class, credentials());
        assertEquals(SERVICE_ACCOUNT_EMAIL, credentials.getClientEmail());
    }

    @Test
    void getContextUsesTheBase64ServiceAccountFromTheConfigWhenTheContextServiceAccountIsInvalid() {
        TestConfigUtil.registerConfig(Map.of("sb.google.serviceaccountjson",
                Base64.getEncoder().encodeToString(serviceAccountJson.getBytes(StandardCharsets.UTF_8))));

        getContext(Map.of("google_service_account_json", "not json"), args());

        final ServiceAccountCredentials credentials = assertInstanceOf(ServiceAccountCredentials.class, credentials());
        assertEquals(SERVICE_ACCOUNT_EMAIL, credentials.getClientEmail());
    }

    @Test
    void getContextReadsTheDocumentIdFromTheConfigFirst() {
        TestConfigUtil.registerConfig(Map.of("sb.google.doc", "config-doc"));
        googleDocsClient.addDocument("config-doc", document(paragraph(text("Config document\n"))));

        final List<RagDocumentContext<Void>> context = getContext(env(), args());

        assertEquals(List.of("Config document\n"), documents(context));
        assertEquals("config-doc", lastCall().documentId());
    }

    @Test
    void getContextReturnsNothingWithoutADocumentId() {
        assertEquals(List.of(), getContext(env(), List.of()));
        assertTrue(googleDocsClient.getCalls().isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheDocumentCanNotBeDownloaded() {
        googleDocsClient.failWith(new IllegalStateException("Google is down"));

        assertEquals(List.of(), getContext(env(), args()));
        assertEquals(1, googleDocsClient.getCalls().size());
    }

    @Test
    void getContextRatesTheDocumentAgainstTheFilterQuestion() {
        rating.set(" 7 ");

        final List<RagDocumentContext<Void>> context = getContext(env(), args(
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is this about queues?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5"));

        assertEquals(List.of(new MetaObjectResult(GoogleDocs.GOOGLE_DOC_FILTER_RATING_META, 7, DOC_ID, TOOL)), context.getFirst().getMetadata());
        assertEquals(List.of(List.of("Is this about queues?")), ratingPrompts);
        final Map<String, String> ratingEnvironment = ratingEnvironments.getFirst();
        assertEquals("The service uses a queue.\n", ratingEnvironment.get(RatingTool.RATING_DOCUMENT_CONTEXT_ARG));
        assertEquals("GoogleDocs[doc-1]", ratingEnvironment.get(HashMapEnvironmentSettings.TOOL_CALLS));
        assertEquals("enc(token-1)", ratingEnvironment.get("google_access_token"));
    }

    @ParameterizedTest
    @CsvSource({"5, 1", "4, 0", "high, 0"})
    void getContextKeepsDocumentsThatMeetTheMinimumRating(final String documentRating, final int expectedDocuments) {
        rating.set(documentRating);

        final List<RagDocumentContext<Void>> context = getContext(env(), args(
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is this about queues?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5"));

        // A rating that is not a number is treated as 0
        assertEquals(expectedDocuments, context.size());
    }

    @ParameterizedTest
    @CsvSource({"6, 1", "2, 0"})
    void getContextUsesTheDefaultRatingWhenRatingFails(final String defaultRating, final int expectedDocuments) {
        ratingFails.set(true);

        final List<RagDocumentContext<Void>> context = getContext(env(), args(
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is this about queues?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5",
                CommonArguments.DEFAULT_RATING_ARG, defaultRating));

        assertEquals(expectedDocuments, context.size());
        context.forEach(doc -> assertEquals(List.of(new MetaObjectResult(GoogleDocs.GOOGLE_DOC_FILTER_RATING_META, 6, DOC_ID, TOOL)), doc.getMetadata()));
    }

    @Test
    void getContextSummarisesTheDocumentWhenRequested() {
        final List<RagDocumentContext<Void>> context = getContext(env(), args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true"));

        assertEquals(List.of("Short summary"), documents(context));
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(List.of("Summarise the document in three paragraphs"), request.document().getPrompts());
        assertEquals(List.of("The service uses a queue.\n"), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void getContextSummarisesWithTheSuppliedPrompt() {
        getContext(env(), args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true", CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "One line please"));

        assertEquals(List.of("One line please"), llmClient.requests.getFirst().document().getPrompts());
    }

    @Test
    void getContextReturnsNothingWhenTheSummaryFails() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        assertEquals(List.of(), getContext(env(), args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true")));
    }

    @Test
    void getContextTrimsTheDocumentToTheKeywords() {
        googleDocsClient.addDocument(DOC_ID, document(paragraph(text("Alpha. " + "filler ".repeat(100) + "The queue is durable. " + "padding ".repeat(100)))));

        final String document = getContext(env(), args(CommonArguments.KEYWORDS_ARG, "durable", CommonArguments.KEYWORD_WINDOW_ARG, "40"))
                .getFirst().document();

        assertTrue(document.contains("durable"), document);
        assertFalse(document.contains("Alpha"), document);
        assertTrue(document.length() < 100, document);
    }

    @Test
    void getContextReturnsNothingWhenTheKeywordsAreMissing() {
        assertEquals(List.of(), getContext(env(), args(CommonArguments.KEYWORDS_ARG, "kafka")));
    }

    @Test
    void getContextAppliesThePreinitializationAndPreprocessingHooks() {
        final List<RagDocumentContext<Void>> context = getContext(env(), args(
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        assertEquals(List.of("HOOK DOCUMENT", "THE SERVICE USES A QUEUE.\n"), documents(context));
        assertEquals(List.of("append", DOC_ID), context.stream().map(RagDocumentContext::id).toList());
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        assertEquals(List.of("The service uses a queue.\n"), documents(tool.getContext(env(), List.of(), args())));
    }

    @Test
    void callAnswersFromTheDocument() {
        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post"));

        assertEquals("answer [post]", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env(), request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).startsWith("You are an expert in reading technical Google Documents."));
        assertEquals(List.of("The service uses a queue.\n"), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callStillAsksTheLlmWhenTheDocumentCanNotBeDownloaded() {
        googleDocsClient.failWith(new IllegalStateException("Google is down"));

        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args());

        assertEquals("answer", result.getResponse());
        assertEquals(List.of(), llmClient.requests.getFirst().document().getIndividualContexts());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args()));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 37 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }
}
