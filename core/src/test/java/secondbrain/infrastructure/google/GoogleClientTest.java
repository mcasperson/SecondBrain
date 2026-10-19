package secondbrain.infrastructure.google;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.test.DirectHttpClientCaller;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.google.api.*;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GoogleClient.class)
@AddBeanClasses(Loggers.class)
// The Google API records are built with null fields to match real responses
@SuppressWarnings("NullAway")
class GoogleClientTest {
    private static final String DEFAULT_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String DEFAULT_MODEL = "gemini-2.0-flash";
    private static final String TOOL = "TestTool";
    private static final String SOURCE = "GoogleLLM";
    private static final String EXCEPTION_MESSAGE = "Failed to get comments from ZenDesk API";

    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final DirectHttpClientCaller httpCaller = new DirectHttpClientCaller();
    private final FakeJaxRs http = new FakeJaxRs();
    private final Map<String, String> settings = new HashMap<>();

    @Inject
    private GoogleClient client;

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    TimeoutHttpClientCaller produceHttpClientCaller() {
        return httpCaller;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(http.client);
    }

    @BeforeEach
    void registerConfig() {
        settings.put("sb.googlellm.apikey", "google-key");
        TestConfigUtil.registerConfig(settings);
    }

    /**
     * Overrides a setting. Must be called before the client is first used, as CDI creates it lazily.
     */
    private void configure(final String key, final String value) {
        settings.put(key, value);
        TestConfigUtil.registerConfig(settings);
    }

    private static GoogleResponse answer(final String... texts) {
        return new GoogleResponse(
                List.of(new GoogleResponseCandidates(new GoogleResponseCandidatesContent(
                        Arrays.stream(texts).map(GoogleResponseCandidatesContentParts::new).toList()))),
                null);
    }

    private GoogleRequest postedRequest(final int index) {
        return (GoogleRequest) http.postedEntities().get(index);
    }

    private static List<String> texts(final List<GoogleRequestContentsParts> parts) {
        return parts.stream().map(GoogleRequestContentsParts::getText).toList();
    }

    private static RagMultiDocumentContext<Void> ragDocs(final List<String> prompts) {
        return new RagMultiDocumentContext<>(prompts, "Be brief", List.of(
                new RagDocumentContext<>(TOOL, "Ticket", "printer broken", List.of()),
                new RagDocumentContext<>(TOOL, "Call", "customer angry", List.of())));
    }

    @Test
    void callPostsThePromptToTheDefaultModelAndJoinsEveryPart() {
        http.enqueue(FakeJaxRs.response(200, new GoogleResponse(List.of(
                new GoogleResponseCandidates(new GoogleResponseCandidatesContent(List.of(
                        new GoogleResponseCandidatesContentParts("Hello"),
                        new GoogleResponseCandidatesContentParts(" world")))),
                new GoogleResponseCandidates(new GoogleResponseCandidatesContent(List.of(
                        new GoogleResponseCandidatesContentParts("!"))))), null)));

        final String result = client.call("Say hello", Map.of());

        assertEquals("Hello world!", result);
        assertEquals(List.of(DEFAULT_URL + DEFAULT_MODEL + ":generateContent"), http.targets());
        assertEquals(List.of("google-key"), http.headers("X-goog-api-key"));
        assertEquals(List.of("application/json"), http.headers("Content-Type"));
        assertEquals(List.of("Say hello"), texts(postedRequest(0).getContents().getFirst().getParts()));
        assertNull(postedRequest(0).systemInstruction());
        // Direct calls are not cached
        assertTrue(localStorage.calls.isEmpty());
    }

    @Test
    void callPassesTheRetrySettingsToTheHttpCaller() {
        configure("sb.googlellm.retryCount", "5");
        configure("sb.googlellm.retryDelaySeconds", "7");
        http.enqueue(FakeJaxRs.response(200, answer("ok")));

        client.call("Prompt", Map.of());

        assertEquals(List.of(5), httpCaller.maxRetries());
        assertEquals(List.of(7L), httpCaller.retryDelays());
    }

    @Test
    void callUsesDefaultRetrySettings() {
        http.enqueue(FakeJaxRs.response(200, answer("ok")));

        client.call("Prompt", Map.of());

        assertEquals(List.of(3), httpCaller.maxRetries());
        assertEquals(List.of(30L), httpCaller.retryDelays());
    }

    @Test
    void callWithModelIgnoresTheSuppliedModelAndUsesTheConfiguredOne() {
        configure("sb.googlellm.url", "https://google.test/models/");
        configure("sb.googlellm.model", "gemini-configured");
        http.enqueue(FakeJaxRs.response(200, answer("ok")));

        assertEquals("ok", client.call("Prompt", "gemini-pro", Map.of()));
        // Looks like a bug: call(prompt, model, settings) delegates to call(request), which reads the model field
        // instead of the model argument
        assertEquals(List.of("https://google.test/models/gemini-configured:generateContent"), http.targets());
    }

    @Test
    void callRejectsBlankPromptsAndModels() {
        assertThrows(IllegalArgumentException.class, () -> client.call(" ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> client.call("Prompt", "", Map.of()));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void callRequiresAnApiKey() {
        settings.remove("sb.googlellm.apikey");
        TestConfigUtil.registerConfig(settings);

        assertThrows(IllegalStateException.class, () -> client.call("Prompt", Map.of()));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void callWrapsGoogleErrorsWithTheirCode() {
        http.enqueue(FakeJaxRs.response(429, new GoogleResponse(null, new GoogleResponseError("Quota exceeded", 429, "RESOURCE_EXHAUSTED"))));

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> client.call("Prompt", Map.of()));

        // The wrapper message names the wrong service, but this is the current behaviour
        assertEquals(EXCEPTION_MESSAGE, ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals("Google LLM Error: Quota exceeded (Code: 429)", ex.getCause().getMessage());
    }

    @Test
    void callWrapsGoogleErrorsWithoutACode() {
        http.enqueue(FakeJaxRs.response(500, new GoogleResponse(null, new GoogleResponseError("Internal", null, null))));

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> client.call("Prompt", Map.of()));

        assertEquals("Google LLM Error: Internal", ex.getCause().getMessage());
    }

    @Test
    void callRejectsAnEmptyResponseBody() {
        http.enqueue(FakeJaxRs.response(200, null));

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> client.call("Prompt", Map.of()));

        assertEquals(EXCEPTION_MESSAGE, ex.getMessage());
        assertEquals("Google LLM response is empty or invalid.", ex.getCause().getMessage());
    }

    @Test
    void callReturnsAnEmptyStringWhenThereAreNoCandidates() {
        http.enqueue(FakeJaxRs.response(200, new GoogleResponse(null, null)));

        assertEquals("", client.call("Prompt", Map.of()));
    }

    @Test
    void callWithCacheSendsEachPromptWithTheContextAndInstructions() {
        http.enqueue(FakeJaxRs.response(200, answer("first answer")))
                .enqueue(FakeJaxRs.response(200, answer("second answer")));

        final RagMultiDocumentContext<Void> result = client.callWithCache(ragDocs(List.of("Summarise", "List actions")), Map.of(), TOOL);

        assertEquals(List.of("first answer", "second answer"), result.getResponses());
        assertEquals(List.of("Ticket: printer broken", "Call: customer angry", "Summarise"),
                texts(postedRequest(0).getContents().getFirst().getParts()));
        assertEquals(List.of("Ticket: printer broken", "Call: customer angry", "List actions"),
                texts(postedRequest(1).getContents().getFirst().getParts()));
        assertEquals(List.of("Be brief"), texts(postedRequest(0).getSystemInstruction().getParts()));
        assertEquals(List.of(DEFAULT_URL + DEFAULT_MODEL + ":generateContent", DEFAULT_URL + DEFAULT_MODEL + ":generateContent"), http.targets());
    }

    @Test
    void callWithCacheCachesEachPromptForThirtyDaysByDefault() {
        http.enqueue(FakeJaxRs.response(200, answer("answer")));

        client.callWithCache(ragDocs(List.of("Summarise")), Map.of(), TOOL);

        final String expectedHash = DigestUtils.sha256Hex(postedRequest(0).generatePromptText() + DEFAULT_MODEL + DEFAULT_URL);
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, SOURCE, expectedHash, 30L * 24 * 60 * 60)), localStorage.calls);
    }

    @Test
    void callWithCacheUsesTheConfiguredTtl() {
        configure("sb.googlellm.ttldDays", "2");
        http.enqueue(FakeJaxRs.response(200, answer("answer")));
        client.callWithCache(ragDocs(List.of("Summarise")), Map.of(), TOOL);
        assertEquals(2L * 24 * 60 * 60, localStorage.calls.getLast().ttlSeconds());
    }

    @Test
    void callWithCacheFallsBackToNinetyDaysForAnInvalidTtl() {
        configure("sb.googlellm.ttldDays", "forever");
        http.enqueue(FakeJaxRs.response(200, answer("answer")));
        client.callWithCache(ragDocs(List.of("Summarise")), Map.of(), TOOL);
        assertEquals(90L * 24 * 60 * 60, localStorage.calls.getLast().ttlSeconds());
    }

    @Test
    void callWithCacheHonoursUrlAndModelOverrides() {
        http.enqueue(FakeJaxRs.response(200, answer("answer")));

        client.callWithCache(ragDocs(List.of("Summarise")),
                Map.of(LlmClient.URL_OVERRIDE_ENV, "https://override.test/", LlmClient.MODEL_OVERRIDE_ENV, "gemini-override"),
                TOOL);

        assertEquals(List.of("https://override.test/gemini-override:generateContent"), http.targets());
        final String expectedHash = DigestUtils.sha256Hex(postedRequest(0).generatePromptText() + "gemini-override" + "https://override.test/");
        assertEquals(expectedHash, localStorage.calls.getFirst().promptHash());
    }

    @Test
    void callWithCacheReturnsCachedResponsesWithoutCallingGoogle() {
        localStorage.prime(SOURCE, "cached answer");

        final RagMultiDocumentContext<Void> result = client.callWithCache(ragDocs(List.of("Summarise")), Map.of(), TOOL);

        assertEquals(List.of("cached answer"), result.getResponses());
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void callWithCacheValidatesItsArguments() {
        assertThrows(IllegalArgumentException.class, () -> client.callWithCache(ragDocs(List.of("Summarise")), Map.of(), " "));
        assertThrows(NullPointerException.class, () -> client.callWithCache(null, Map.of(), TOOL));
        assertThrows(IllegalArgumentException.class, () -> client.callWithCache(ragDocs(List.of("Summarise")), Map.of(LlmClient.URL_OVERRIDE_ENV, ""), TOOL));
        assertEquals(0, httpCaller.calls());
    }
}
