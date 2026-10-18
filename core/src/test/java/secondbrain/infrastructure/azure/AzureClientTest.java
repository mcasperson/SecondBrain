package secondbrain.infrastructure.azure;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.answer.AnswerFormatterService;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.FailedAzure;
import secondbrain.domain.exceptions.LlmCallFailure;
import secondbrain.domain.exceptions.RateLimit;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.limit.ListLimiterAtomicCutOff;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.list.TrimmedCommaSeparatedStringToList;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.MutexCallback;
import secondbrain.domain.mutex.Semaphore;
import secondbrain.domain.persist.CacheResult;
import secondbrain.domain.persist.GenerateValue;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.persist.MockLocalStorage;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.test.DirectHttpClientCaller;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.azure.api.AzureRequestMessage;
import secondbrain.infrastructure.azure.api.PromptTextGenerator;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(AzureClient.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(ListLimiterAtomicCutOff.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(MessageTooLongResponseInspector.class)
@AddBeanClasses(TrimmedCommaSeparatedStringToList.class)
@AddBeanClasses(JsonDeserializerJackson.class)
class AzureClientTest {
    private static final String URL = "https://azure.example/openai/deployments/x/chat/completions?api-version=2024-05-01-preview";
    private static final String TOO_LONG_BODY = "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"Please reduce the length of the messages.\",\"status\":400}}";

    private final DirectHttpClientCaller httpCaller = new DirectHttpClientCaller();
    private final RecordingSemaphore semaphore = new RecordingSemaphore();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final FakeJaxRs http = new FakeJaxRs();
    private final Map<String, String> settings = new HashMap<>();
    // A unique model per test gives each test its own static rate limiter
    private final String model = "model-" + UUID.randomUUID();

    @Inject
    private AzureClient azureClient;

    @Produces
    @Preferred
    Semaphore produceSemaphore() {
        return semaphore;
    }

    @Produces
    TimeoutHttpClientCaller produceHttpClientCaller() {
        return httpCaller;
    }

    @Produces
    AnswerFormatterService produceAnswerFormatter() {
        return (m, r) -> r.trim();
    }

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(http.client);
    }

    @Produces
    @Identifier("financialLocationContactRedaction")
    SanitizeDocument produceSanitizer() {
        return new PhoneRedactor();
    }

    @BeforeEach
    void registerConfig() {
        settings.put("sb.azurellm.apikey", "secret-key");
        settings.put("sb.azurellm.url", URL);
        settings.put("sb.azurellm.model", model);
        settings.put("sb.azurellm.maxInputTokens", "14336");
        settings.put("sb.azurellm.ttlDays", "30");
        settings.put("sb.azurellm.trimIfTooLongFraction", "0.7");
        settings.put("sb.azurellm.retryCount", "3");
        settings.put("sb.azurellm.retryDelaySeconds", "0");
        settings.put("sb.azurellm.lock", "sb_azure.lock");
        settings.put("sb.azurellm.concurrency", "5");
        settings.put("sb.azurellm.rateLimitPerSecond", "1000");
        TestConfigUtil.registerConfig(settings);
    }

    /**
     * Overrides a setting. Must be called before the first call to the client, which is created lazily by CDI.
     */
    private void configure(final String key, final String value) {
        settings.put(key, value);
        TestConfigUtil.registerConfig(settings);
    }

    private void unconfigure(final String key) {
        settings.remove(key);
        TestConfigUtil.registerConfig(settings);
    }

    private static String choice(final String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    private static Response ok(final String content) {
        return FakeJaxRs.response(200, choice(content));
    }

    /**
     * A failed response that also interrupts the calling thread. Rate limited calls sleep for a hard-coded
     * 180 seconds before retrying; with an interrupt pending, that sleep aborts immediately with an
     * InterruptedException (vavr treats it as fatal and rethrows it), which proves the backoff branch was taken.
     */
    private static Response failedAndInterrupt(final int status, final String body) {
        final Response response = FakeJaxRs.response(status, body);
        when(response.getStatus()).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return status;
        });
        return response;
    }

    private PromptTextGenerator postedRequest(final int index) {
        return (PromptTextGenerator) http.postedEntities().get(index);
    }

    @Test
    void callRejectsBlankPrompt() {
        assertThrows(IllegalArgumentException.class, () -> azureClient.call(" ", Map.of()));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void callSendsSanitizedPromptWithConfiguredModelAndCredentials() {
        http.enqueue(ok(" the answer "));

        final String result = azureClient.call("Call me on 555-1234", Map.of());

        assertEquals("the answer", result);
        assertEquals(List.of(URL), http.targets());
        assertEquals(List.of("Bearer secret-key"), http.headers("Authorization"));
        final PromptTextGenerator request = postedRequest(0);
        assertEquals(model, request.getModel());
        assertEquals(List.of(new AzureRequestMessage("user", "Call me on [phone]")), request.getMessages());
        assertEquals(List.of("sb_azure.lock." + model), semaphore.lockNames);
        assertEquals(List.of(5), semaphore.allowed);
    }

    @Test
    void callWithExplicitModelUsesThatModelForRequestAndLock() {
        http.enqueue(ok("x"));

        azureClient.call("prompt", "gpt-explicit-" + model, Map.of());

        assertEquals("gpt-explicit-" + model, postedRequest(0).getModel());
        assertEquals(List.of("sb_azure.lock.gpt-explicit-" + model), semaphore.lockNames);
    }

    @Test
    void callWithoutApiKeyFailsBeforeAnyHttpCall() {
        unconfigure("sb.azurellm.apikey");

        final IllegalStateException ex = assertThrows(IllegalStateException.class, () -> azureClient.call("prompt", Map.of()));

        assertTrue(String.valueOf(ex.getMessage()).contains("sb.azurellm.apikey"));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void callWithoutUrlFailsBeforeAnyHttpCall() {
        unconfigure("sb.azurellm.url");

        final IllegalStateException ex = assertThrows(IllegalStateException.class, () -> azureClient.call("prompt", Map.of()));

        assertTrue(String.valueOf(ex.getMessage()).contains("sb.azurellm.url"));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void callBacksOffInsteadOfFailingForRateLimitAndServerErrors(final int status) {
        http.enqueue(failedAndInterrupt(status, "busy")).enqueue(ok("recovered"));

        final Throwable thrown = assertThrows(Throwable.class, () -> azureClient.call("prompt", Map.of()));

        // The backoff sleep was reached (and aborted by the pending interrupt) rather than failing with FailedAzure
        assertInstanceOf(InterruptedException.class, thrown);
        assertEquals(1, httpCaller.calls());
        assertFalse(Thread.interrupted());
    }

    @Test
    void callTrimsMessagesAndRetriesWhenAzureReportsMessageTooLong() {
        http.enqueue(FakeJaxRs.response(400, TOO_LONG_BODY)).enqueue(ok("short answer"));
        // Three messages of 10 characters each. A fraction of 0.7 keeps 21 characters, so only the first two survive.
        final RagMultiDocumentContext<Void> docs = new RagMultiDocumentContext<>(
                List.of("PPPPPPPPPP"),
                "SSSSSSSSSS",
                List.of(new RagDocumentContext<>("tool", "L", "DDDDDDDD", List.of())));

        final RagMultiDocumentContext<Void> result = azureClient.callWithCache(docs, Map.of(), "tool");

        assertEquals(List.of("short answer"), result.getResponses());
        assertEquals(3, postedRequest(0).getMessages().size());
        assertEquals(List.of(
                        new AzureRequestMessage("system", "SSSSSSSSSS"),
                        new AzureRequestMessage("user", "L: DDDDDDDD")),
                postedRequest(1).getMessages());
    }

    @Test
    void callDoesNotRetryOtherClientErrors() {
        http.enqueue(FakeJaxRs.response(400, "{\"error\":{\"message\":\"bad request\"}}"));

        final FailedAzure ex = assertThrows(FailedAzure.class, () -> azureClient.call("prompt", Map.of()));

        assertTrue(String.valueOf(ex.getMessage()).contains(URL));
        assertEquals(1, httpCaller.calls());
    }

    @Test
    void callRetriesBlankResponsesUntilTheRetryLimitIsExceeded() {
        // Attempts 0..10 are made; the 12th attempt exceeds RATELIMIT_API_RETRIES and fails without calling the API
        for (int i = 0; i < 11; i++) {
            http.enqueue(ok(" "));
        }

        assertThrows(RateLimit.class, () -> azureClient.call("prompt", Map.of()));
        assertEquals(11, httpCaller.calls());
    }

    @Test
    void callWithCacheBuildsMessagesAndCachesEachPromptWithConfiguredTtl() {
        http.enqueue(ok("first")).enqueue(ok("second"));
        final RagMultiDocumentContext<Void> docs = new RagMultiDocumentContext<>(
                List.of("prompt one", "prompt two"),
                "be helpful",
                List.of(new RagDocumentContext<>("tool", "Doc A", "alpha", List.of()),
                        new RagDocumentContext<>("tool", "Doc B", "beta", List.of())));

        final RagMultiDocumentContext<Void> result = azureClient.callWithCache(docs, Map.of(), "MyTool");

        assertEquals(List.of("first", "second"), result.getResponses());
        assertEquals(List.of(
                        new AzureRequestMessage("system", "be helpful"),
                        new AzureRequestMessage("user", "Doc A: alpha"),
                        new AzureRequestMessage("user", "Doc B: beta"),
                        new AzureRequestMessage("user", "prompt one")),
                postedRequest(0).getMessages());
        assertEquals("prompt two", postedRequest(1).getMessages().getLast().content());
        assertEquals(List.of("getOrPutString:MyTool:AzureLLMV3:" + (30 * 24 * 60 * 60), "getOrPutString:MyTool:AzureLLMV3:" + (30 * 24 * 60 * 60)),
                localStorage.calls);
        assertNotEquals(localStorage.hashes.get(0), localStorage.hashes.get(1), "each prompt must have its own cache key");
    }

    @Test
    void callWithCacheUsesDefaultTtlWhenTtlDaysIsInvalid() {
        configure("sb.azurellm.ttlDays", "not-a-number");
        http.enqueue(ok("x"));

        azureClient.callWithCache(new RagMultiDocumentContext<>(List.of("p"), "i", List.of()), Map.of(), "MyTool");

        assertEquals(List.of("getOrPutString:MyTool:AzureLLMV3:" + (365 * 24 * 60 * 60)), localStorage.calls);
    }

    @Test
    void callWithCacheWithReadCacheDisabledCallsApiAndRefreshesCache() {
        configure("sb.azurellm.disableToolReadCache", " OtherTool, MyTool ");
        http.enqueue(ok("fresh"));

        final RagMultiDocumentContext<Void> result = azureClient.callWithCache(
                new RagMultiDocumentContext<>(List.of("p"), "i", List.of()), Map.of(), "MyTool");

        assertEquals(List.of("fresh"), result.getResponses());
        assertEquals(List.of("putString:MyTool:AzureLLMV3:" + (30 * 24 * 60 * 60) + "=fresh"), localStorage.calls);
    }

    @Test
    void callWithCacheWithWriteCacheDisabledReturnsCachedValueWithoutCallingApi() {
        configure("sb.azurellm.disableToolWriteCache", "MyTool");
        localStorage.cachedString = new CacheResult<>("cached answer", null, true);

        final RagMultiDocumentContext<Void> result = azureClient.callWithCache(
                new RagMultiDocumentContext<>(List.of("p"), "i", List.of()), Map.of(), "MyTool");

        assertEquals(List.of("cached answer"), result.getResponses());
        assertEquals(List.of("getString:MyTool:AzureLLMV3"), localStorage.calls);
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void callWithCacheWithWriteCacheDisabledFallsBackToApiWhenCacheReadFails() {
        configure("sb.azurellm.disableToolWriteCache", "MyTool");
        localStorage.failGetString = true;
        http.enqueue(ok("from api"));

        final RagMultiDocumentContext<Void> result = azureClient.callWithCache(
                new RagMultiDocumentContext<>(List.of("p"), "i", List.of()), Map.of(), "MyTool");

        assertEquals(List.of("from api"), result.getResponses());
        assertEquals(1, httpCaller.calls());
    }

    @Test
    void callWithCacheWithReadAndWriteDisabledBypassesCacheEntirely() {
        configure("sb.azurellm.disableToolReadCache", "MyTool");
        configure("sb.azurellm.disableToolWriteCache", "MyTool");
        http.enqueue(ok("direct"));

        final RagMultiDocumentContext<Void> result = azureClient.callWithCache(
                new RagMultiDocumentContext<>(List.of("p"), "i", List.of()), Map.of(), "MyTool");

        assertEquals(List.of("direct"), result.getResponses());
        assertTrue(localStorage.calls.isEmpty());
    }

    @Test
    void callWithCacheAggregatesFailuresAsSuppressedExceptions() {
        final RuntimeException failure = new RuntimeException("llm down");
        localStorage.forcedResult = new CacheResult<>(null, failure, false);

        final LlmCallFailure ex = assertThrows(LlmCallFailure.class, () -> azureClient.callWithCache(
                new RagMultiDocumentContext<>(List.of("p1", "p2"), "i", List.of()), Map.of(), "MyTool"));

        assertEquals(List.of(failure, failure), List.of(ex.getSuppressed()));
    }

    @Test
    void callWithCacheTrimsEachDocumentToTheContextWindowOverride() {
        http.enqueue(ok("x"));
        // A 10 token window allows 35 characters per document
        final String document = "0123456789".repeat(10);

        azureClient.callWithCache(
                new RagMultiDocumentContext<>(List.of("p"), "i", List.of(new RagDocumentContext<>("tool", "Doc", document, List.of()))),
                Map.of(LlmClient.CONTEXT_WINDOW_OVERRIDE_ENV, "10"),
                "MyTool");

        assertEquals("Doc: " + document.substring(0, 35), postedRequest(0).getMessages().get(1).content());
    }

    @Test
    void callWithCacheHonoursModelAndUrlOverrides() {
        final String overrideUrl = "https://override.example/chat?api-version=2025-04-01-preview";
        http.enqueue(ok("x"));

        azureClient.callWithCache(
                new RagMultiDocumentContext<>(List.of("p"), "i", List.of()),
                Map.of(LlmClient.MODEL_OVERRIDE_ENV, "override-" + model, LlmClient.URL_OVERRIDE_ENV, overrideUrl),
                "MyTool");

        assertEquals(List.of(overrideUrl), http.targets());
        assertEquals("override-" + model, postedRequest(0).getModel());
        assertEquals(List.of("sb_azure.lock.override-" + model), semaphore.lockNames);
    }

    @Test
    void callWithCacheRejectsBlankTool() {
        assertThrows(IllegalArgumentException.class, () -> azureClient.callWithCache(
                new RagMultiDocumentContext<>(List.of("p"), "i", List.of()), Map.of(), " "));
    }

    private static final class PhoneRedactor implements SanitizeDocument {
        @Override
        public @Nullable String sanitize(final @Nullable String document) {
            return document == null ? null : document.replace("555-1234", "[phone]");
        }

        @Override
        public @Nullable String sanitize(final @Nullable String document, final boolean unsanitize) {
            return sanitize(document);
        }
    }

    private static final class RecordingSemaphore implements Semaphore {
        final List<String> lockNames = new ArrayList<>();
        final List<Integer> allowed = new ArrayList<>();

        @Override
        public <T> T acquire(final int allowedCount, final long timeoutMilliseconds, final String lockName, final MutexCallback<T> callback) {
            return acquire(allowedCount, lockName, callback);
        }

        @Override
        public <T> T acquire(final int allowedCount, final String lockName, final MutexCallback<T> callback) {
            lockNames.add(lockName);
            allowed.add(allowedCount);
            return callback.apply();
        }
    }

    private static final class RecordingLocalStorage extends MockLocalStorage {
        final List<String> calls = new ArrayList<>();
        final List<String> hashes = new ArrayList<>();
        @Nullable CacheResult<String> cachedString;
        @Nullable CacheResult<String> forcedResult;
        boolean failGetString;

        @Override
        public CacheResult<String> getString(final String tool, final String source, final String promptHash) {
            calls.add("getString:" + tool + ":" + source);
            if (failGetString) {
                throw new IllegalStateException("cache unavailable");
            }
            return cachedString != null ? cachedString : new CacheResult<>(null, null, false);
        }

        @Override
        public CacheResult<String> getOrPutString(final String tool, final String source, final String promptHash, final long ttlSeconds, final GenerateValue<String> generateValue) {
            calls.add("getOrPutString:" + tool + ":" + source + ":" + ttlSeconds);
            hashes.add(promptHash);
            if (forcedResult != null) {
                return forcedResult;
            }
            return new CacheResult<>(generateValue.generate(), null, false);
        }

        @Override
        public void putString(final String tool, final String source, final String promptHash, final long ttlSeconds, final String response) {
            calls.add("putString:" + tool + ":" + source + ":" + ttlSeconds + "=" + response);
        }
    }
}
