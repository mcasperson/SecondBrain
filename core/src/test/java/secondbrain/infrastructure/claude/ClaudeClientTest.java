package secondbrain.infrastructure.claude;

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
import secondbrain.domain.exceptions.RateLimit;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.ListLimiterAtomicCutOff;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.test.FakeClaudeMessages;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ClaudeClient.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(ListLimiterAtomicCutOff.class)
class ClaudeClientTest {
    private static final String DEFAULT_MODEL = "claude-haiku-5-5";
    private static final String TOOL = "TestTool";
    private static final String SOURCE = "ClaudeLLM";

    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final FakeClaudeMessages claudeMessages = new FakeClaudeMessages();
    private final Map<String, String> settings = new HashMap<>();

    @Inject
    private ClaudeClient client;

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    ClaudeMessages produceClaudeMessages() {
        return claudeMessages;
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(settings);
    }

    /**
     * Overrides a setting. Must be called before the client is first used, as CDI creates it lazily.
     */
    private void configure(final String key, final String value) {
        settings.put(key, value);
        TestConfigUtil.registerConfig(settings);
    }

    private static RagMultiDocumentContext<Void> ragDocs(final List<String> prompts) {
        return new RagMultiDocumentContext<>(prompts, "Be brief", List.of(
                new RagDocumentContext<>(TOOL, "Ticket", "printer broken", List.of()),
                new RagDocumentContext<>(TOOL, "Call", "customer angry", List.of())));
    }

    @Test
    void callSendsThePromptToTheDefaultModel() {
        claudeMessages.respondWith("Hello");

        assertEquals("Hello", client.call("Say hello", Map.of()));

        final ClaudeRequest request = claudeMessages.getRequests().getFirst();
        assertEquals(DEFAULT_MODEL, request.model());
        assertEquals(16000L, request.maxTokens());
        assertNull(request.system());
        assertNull(request.effort());
        assertEquals(List.of("Say hello"), request.messages());
        // Direct calls are not cached
        assertTrue(localStorage.calls.isEmpty());
    }

    @Test
    void callUsesTheConfiguredModelTokensAndEffort() {
        configure("sb.claudellm.model", "claude-sonnet-5-5");
        configure("sb.claudellm.maxOutputTokens", "2048");
        configure("sb.claudellm.reasoningEffort", "high");

        client.call("Prompt", Map.of());

        final ClaudeRequest request = claudeMessages.getRequests().getFirst();
        assertEquals("claude-sonnet-5-5", request.model());
        assertEquals(2048L, request.maxTokens());
        assertEquals("high", request.effort());
    }

    @Test
    void callWithModelUsesTheSuppliedModelAndEffortOverride() {
        client.call("Prompt", "claude-sonnet-5-5", Map.of(LlmClient.REASONING_EFFORT_OVERRIDE_ENV, "low"));

        final ClaudeRequest request = claudeMessages.getRequests().getFirst();
        assertEquals("claude-sonnet-5-5", request.model());
        assertEquals("low", request.effort());
    }

    @Test
    void callRejectsBlankPromptsAndModels() {
        assertThrows(IllegalArgumentException.class, () -> client.call(" ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> client.call("Prompt", "", Map.of()));
        assertTrue(claudeMessages.getRequests().isEmpty());
    }

    @Test
    void callPassesFailuresThrough() {
        claudeMessages.failWith(new RateLimit("slow down"));

        assertThrows(RateLimit.class, () -> client.call("Prompt", Map.of()));
    }

    @Test
    void callWithCacheSendsEachPromptWithTheContextAndInstructions() {
        final RagMultiDocumentContext<Void> result = client.callWithCache(ragDocs(List.of("Summarise", "List actions")), Map.of(), TOOL);

        assertEquals(List.of("response", "response"), result.getResponses());
        final List<ClaudeRequest> requests = claudeMessages.getRequests();
        assertEquals(2, requests.size());
        assertEquals("Be brief", requests.get(0).system());
        assertEquals(List.of("Ticket: printer broken", "Call: customer angry", "Summarise"), requests.get(0).messages());
        assertEquals(List.of("Ticket: printer broken", "Call: customer angry", "List actions"), requests.get(1).messages());
    }

    @Test
    void callWithCacheCachesEachPromptForThirtyDaysByDefault() {
        client.callWithCache(ragDocs(List.of("Summarise")), Map.of(), TOOL);

        final ClaudeRequest request = claudeMessages.getRequests().getFirst();
        final String expectedHash = DigestUtils.sha256Hex(request.generatePromptText() + DEFAULT_MODEL + 16000L);
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, SOURCE, expectedHash, 30L * 24 * 60 * 60)), localStorage.calls);
    }

    @Test
    void callWithCacheUsesTheConfiguredTtl() {
        configure("sb.claudellm.ttlDays", "2");

        client.callWithCache(ragDocs(List.of("Summarise")), Map.of(), TOOL);

        assertEquals(2L * 24 * 60 * 60, localStorage.calls.getLast().ttlSeconds());
    }

    @Test
    void callWithCacheHonoursModelAndEffortOverrides() {
        client.callWithCache(ragDocs(List.of("Summarise")),
                Map.of(LlmClient.MODEL_OVERRIDE_ENV, "claude-sonnet-5-5", LlmClient.REASONING_EFFORT_OVERRIDE_ENV, "max"),
                TOOL);

        final ClaudeRequest request = claudeMessages.getRequests().getFirst();
        assertEquals("claude-sonnet-5-5", request.model());
        assertEquals("max", request.effort());
        final String expectedHash = DigestUtils.sha256Hex(request.generatePromptText() + "claude-sonnet-5-5" + 16000L + "max");
        assertEquals(expectedHash, localStorage.calls.getFirst().promptHash());
    }

    @Test
    void callWithCacheTrimsContextToTheContextWindow() {
        // 2 tokens at 3.5 characters per token allows 7 characters
        client.callWithCache(ragDocs(List.of("Summarise")), Map.of(LlmClient.CONTEXT_WINDOW_OVERRIDE_ENV, "2"), TOOL);

        final List<String> messages = claudeMessages.getRequests().getFirst().messages();
        assertEquals("Ticket: printer", messages.getFirst());
        assertEquals("Summarise", messages.getLast());
    }

    @Test
    void callWithCacheReturnsCachedResponsesWithoutCallingClaude() {
        localStorage.prime(SOURCE, "cached answer");

        final RagMultiDocumentContext<Void> result = client.callWithCache(ragDocs(List.of("Summarise")), Map.of(), TOOL);

        assertEquals(List.of("cached answer"), result.getResponses());
        assertTrue(claudeMessages.getRequests().isEmpty());
    }

    @Test
    @SuppressWarnings("NullAway")
    void callWithCacheValidatesItsArguments() {
        assertThrows(IllegalArgumentException.class, () -> client.callWithCache(ragDocs(List.of("Summarise")), Map.of(), " "));
        assertThrows(NullPointerException.class, () -> client.callWithCache(null, Map.of(), TOOL));
        assertTrue(claudeMessages.getRequests().isEmpty());
    }
}
