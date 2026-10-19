package secondbrain.infrastructure.ollama;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.answer.AnswerFormatterService;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.prompt.PromptBuilder;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.test.DirectTimeoutService;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.timeout.TimeoutService;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.ollama.api.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.api.OllamaGenerateBodyOptions;
import secondbrain.infrastructure.ollama.api.OllamaResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(OllamaClient.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(ModelConfig.class)
@AddBeanClasses(PromptBuilderSelector.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(Loggers.class)
class OllamaClientTest {
    private static final String URL = "http://ollama.test";
    private static final String TOOL = "TestTool";
    private static final long THIRTY_DAYS = 30L * 24 * 60 * 60;
    private static final String OLLAMA_ERROR = "Predictor is not initialized";

    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final FakeJaxRs http = new FakeJaxRs();
    private final Map<String, String> settings = new HashMap<>();

    @Inject
    private OllamaClient client;

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
    TimeoutService produceTimeoutService() {
        return new DirectTimeoutService();
    }

    @Produces
    AnswerFormatterService produceAnswerFormatterService() {
        // The "raw" model returns the response unchanged, so tests can return the exact Ollama error text
        return (model, response) -> "raw".equals(model) ? response : model + ":" + response;
    }

    /**
     * A prompt builder with an easily predicted format: "instructions|[label]document|prompt".
     */
    @Produces
    PromptBuilder producePromptBuilder() {
        return new PromptBuilder() {
            @Override
            public String modelRegex() {
                return ".*";
            }

            @Override
            public String buildContextPrompt(final String title, final String context) {
                return "[" + title + "]" + context;
            }

            @Override
            public String buildFinalPrompt(@Nullable final String instructions, final String context, final String prompt) {
                return instructions + "|" + context + "|" + prompt;
            }
        };
    }

    @BeforeEach
    void registerConfig() {
        settings.put("sb.ollama.url", URL);
        settings.put("sb.ollama.model", "llama-test");
        TestConfigUtil.registerConfig(settings);
    }

    /**
     * Overrides a setting. Must be called before the client is first used, as CDI creates it lazily.
     */
    private void configure(final String key, final String value) {
        settings.put(key, value);
        TestConfigUtil.registerConfig(settings);
    }

    private static OllamaResponse ollama(final String response) {
        return new OllamaResponse("llama-test", "", "true", "stop", List.of(), "", "", "", "", "", "", response);
    }

    private static RagMultiDocumentContext<Void> document(final List<String> prompts) {
        return new RagMultiDocumentContext<>(prompts, "Be brief", List.of(new RagDocumentContext<>("Tool", "Label", "the document", List.of())));
    }

    private OllamaGenerateBody postedBody(final int index) {
        return (OllamaGenerateBody) http.postedEntities().get(index);
    }

    @Test
    void callSendsThePromptToTheConfiguredModelAndFormatsTheResponse() {
        http.enqueue(FakeJaxRs.response(200, ollama("hello")));

        final String result = client.call("  What is up?  ", Map.of());

        assertEquals("llama-test:hello", result);
        assertEquals(List.of(URL + "/api/generate"), http.targets());
        // The prompt builder is applied once to build the prompt, and again (with no instructions or context)
        // when the document is converted to the final prompt. Simple calls use a fixed context window.
        assertEquals(new OllamaGenerateBody("llama-test", "null||||  What is up?", false, new OllamaGenerateBodyOptions(2048)), postedBody(0));
        assertEquals(List.of("application/json"), http.headers("Content-Type"));
    }

    @Test
    void callWithExplicitModelUsesThatModel() {
        http.enqueue(FakeJaxRs.response(200, ollama("answer")));

        assertEquals("other-model:answer", client.call("question", "other-model", Map.of()));
        assertEquals("other-model", postedBody(0).model());
    }

    @Test
    void callRejectsBlankPromptsAndModels() {
        assertThrows(IllegalArgumentException.class, () -> client.call(" ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> client.call("prompt", " ", Map.of()));
        assertTrue(http.targets().isEmpty());
    }

    @Test
    void callWithCacheBuildsThePromptFromTheContextsAndCachesTheResult() {
        http.enqueue(FakeJaxRs.response(200, ollama("cached answer")));

        final RagMultiDocumentContext<Void> result = client.callWithCache(document(List.of("Question?")), Map.of(), TOOL);

        final String fullPrompt = "Be brief|[Label]the document|Question?";
        assertEquals(List.of("llama-test:cached answer"), result.getResponses());
        assertEquals(new OllamaGenerateBody("llama-test", fullPrompt, false, new OllamaGenerateBodyOptions(4096)), postedBody(0));
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, "LLM",
                DigestUtils.sha256Hex(fullPrompt + "llama-test" + 4096 + URL), THIRTY_DAYS)), localStorage.calls);
    }

    @Test
    void callWithCacheReturnsCachedValueWithoutCallingOllama() {
        localStorage.prime("LLM", "from cache");

        final RagMultiDocumentContext<Void> result = client.callWithCache(document(List.of("Question?")), Map.of(), TOOL);

        assertEquals(List.of("from cache"), result.getResponses());
        assertTrue(http.targets().isEmpty());
    }

    @Test
    void callWithCacheRecallsOllamaWhenTheCachedValueIsAKnownError() {
        localStorage.prime("LLM", OLLAMA_ERROR);
        http.enqueue(FakeJaxRs.response(200, ollama("fresh")));

        final RagMultiDocumentContext<Void> result = client.callWithCache(document(List.of("Question?")), Map.of(), TOOL);

        assertEquals(List.of("llama-test:fresh"), result.getResponses());
        assertEquals(1, http.targets().size());
    }

    @Test
    void callWithCacheDoesNotReturnAKnownErrorFromOllama() {
        http.enqueue(FakeJaxRs.response(200, ollama(OLLAMA_ERROR)));

        // The error is converted to a null cache value, which then fails the call rather than being returned
        assertThrows(NoSuchElementException.class,
                () -> client.callWithCache(document(List.of("Question?")), Map.of("custom_model", "raw"), TOOL));
        assertEquals(1, http.targets().size());
    }

    @Test
    void callWithCacheRawModelResponseIsReturnedWhenItIsNotAnError() {
        http.enqueue(FakeJaxRs.response(200, ollama("Predictor is ready")));

        assertEquals(List.of("Predictor is ready"),
                client.callWithCache(document(List.of("Question?")), Map.of("custom_model", "raw"), TOOL).getResponses());
    }

    @Test
    void callWithCacheAnswersEveryPrompt() {
        http.enqueue(FakeJaxRs.response(200, ollama("one")))
                .enqueue(FakeJaxRs.response(200, ollama("two")));

        final RagMultiDocumentContext<Void> result = client.callWithCache(document(List.of("first", "second")), Map.of(), TOOL);

        assertEquals(List.of("llama-test:one", "llama-test:two"), result.getResponses());
        assertEquals("Be brief|[Label]the document|first", postedBody(0).prompt());
        assertEquals("Be brief|[Label]the document|second", postedBody(1).prompt());
        assertEquals(2, localStorage.calls.size());
    }

    @Test
    void callWithCacheHonoursModelContextWindowAndUrlOverrides() {
        http.enqueue(FakeJaxRs.response(200, ollama("custom")));
        final Map<String, String> env = Map.of(
                "custom_model", "mistral",
                "context_window", "8192",
                LlmClient.URL_OVERRIDE_ENV, "http://other.test");

        final RagMultiDocumentContext<Void> result = client.callWithCache(document(List.of("Question?")), env, TOOL);

        assertEquals(List.of("mistral:custom"), result.getResponses());
        assertEquals(List.of("http://other.test/api/generate"), http.targets());
        assertEquals("mistral", postedBody(0).model());
        assertEquals(8192, postedBody(0).options().num_ctx());
        assertEquals(DigestUtils.sha256Hex("Be brief|[Label]the document|Question?" + "mistral" + 8192 + "http://other.test"),
                localStorage.calls.getFirst().promptHash());
    }

    @Test
    void callWithCacheFallsBackToNinetyDayTtlWhenTtlIsInvalid() {
        configure("sb.ollama.ttldays", "not a number");
        http.enqueue(FakeJaxRs.response(200, ollama("answer")));

        client.callWithCache(document(List.of("Question?")), Map.of(), TOOL);

        assertEquals(90L * 24 * 60 * 60, localStorage.calls.getFirst().ttlSeconds());
    }

    @Test
    @SuppressWarnings("NullAway")
    void callWithCacheRejectsInvalidArguments() {
        assertThrows(NullPointerException.class, () -> client.callWithCache(null, Map.of(), TOOL));
        assertThrows(NullPointerException.class, () -> client.callWithCache(document(List.of("q")), null, TOOL));
        assertThrows(IllegalArgumentException.class, () -> client.callWithCache(document(List.of("q")), Map.of(), " "));
    }

    @Test
    void serverErrorIsRetriedAndTheNextResponseIsUsed() {
        http.enqueue(FakeJaxRs.response(500, "overloaded"))
                .enqueue(FakeJaxRs.response(200, ollama("recovered")));

        assertEquals("llama-test:recovered", client.call("question", Map.of()));
        assertEquals(2, http.targets().size());
    }

    @Test
    void missingModelResponseIsRetriedAndTheNextResponseIsUsed() {
        http.enqueue(FakeJaxRs.response(404, "model not found"))
                .enqueue(FakeJaxRs.response(200, ollama("pulled")));

        assertEquals("llama-test:pulled", client.call("question", Map.of()));
        assertEquals(2, http.targets().size());
    }
}
