package secondbrain.infrastructure.claude;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.collections.MapUtils;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.PromptTooLong;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * ClaudeClient provides access to the Claude API.
 */
@ApplicationScoped
public class ClaudeClient implements LlmClient {
    private static final String CACHE_SOURCE = "ClaudeLLM";
    private static final String DEFAULT_MODEL = "claude-haiku-5-5";
    private static final int DEFAULT_OUTPUT_TOKENS = 16000;
    /**
     * Current Claude models have a 1M token context window, which is shared by the input and output.
     */
    private static final int DEFAULT_INPUT_TOKENS = 1_000_000 - DEFAULT_OUTPUT_TOKENS;
    /**
     * This is an assumption about the number of characters per input token.
     */
    private static final float DEFAULT_CHARS_PER_INPUT_TOKENS = 3.5f;
    private static final int DEFAULT_CACHE_TTL_DAYS = 30;
    private static final int MAX_CONTEXT_REDUCTIONS = 3;
    /**
     * Used to reduce the context when the API does not say how many tokens it accepts.
     */
    private static final double DEFAULT_REDUCTION_RATIO = 0.5;
    private static final double REDUCTION_SAFETY_MARGIN = 0.9;

    @Inject
    @ConfigProperty(name = "sb.claudellm.model", defaultValue = DEFAULT_MODEL)
    private Optional<String> model;

    @Inject
    @ConfigProperty(name = "sb.claudellm.maxOutputTokens", defaultValue = DEFAULT_OUTPUT_TOKENS + "")
    private Optional<String> outputTokens;

    @Inject
    @ConfigProperty(name = "sb.claudellm.maxInputTokens", defaultValue = DEFAULT_INPUT_TOKENS + "")
    private Optional<String> inputTokens;

    /**
     * The effort level: low, medium, high, xhigh or max. Leave empty to use the model's default.
     */
    @Inject
    @ConfigProperty(name = "sb.claudellm.reasoningEffort", defaultValue = "")
    private Optional<String> reasoningEffort;

    @Inject
    @ConfigProperty(name = "sb.claudellm.ttlDays", defaultValue = DEFAULT_CACHE_TTL_DAYS + "")
    private String ttlDays;

    @Inject
    private ClaudeMessages claudeMessages;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private ListLimiter listLimiter;

    @Inject
    private Logger logger;

    @Override
    public String call(final String prompt, final Map<String, String> environmentSettings) {
        checkArgument(StringUtils.isNotBlank(prompt));

        return call(prompt, model.orElse(DEFAULT_MODEL), environmentSettings);
    }

    @Override
    public String call(final String prompt, final String model, final Map<String, String> environmentSettings) {
        checkArgument(StringUtils.isNotBlank(prompt));
        checkArgument(StringUtils.isNotBlank(model));
        checkNotNull(environmentSettings);

        return call(new ClaudeRequest(
                model,
                getMaxOutputTokens(),
                null,
                List.of(prompt),
                getResolvedReasoningEffort(environmentSettings)));
    }

    @Override
    public <T> RagMultiDocumentContext<T> callWithCache(
            final RagMultiDocumentContext<T> ragDocs,
            final Map<String, String> environmentSettings,
            final String tool) {

        checkNotNull(ragDocs);
        checkNotNull(environmentSettings);
        checkArgument(StringUtils.isNotBlank(tool));

        final String modelName = getModelName(environmentSettings);
        final String resolvedReasoningEffort = getResolvedReasoningEffort(environmentSettings);
        final int maxChars = getMaxChars(environmentSettings);

        final List<String> responses = ragDocs.getPrompts()
                .stream()
                .map(prompt -> {
                    final ClaudeRequest request = buildRequest(ragDocs, prompt, modelName, resolvedReasoningEffort, maxChars);

                    final String promptHash = DigestUtils.sha256Hex(request.generatePromptText()
                            + modelName
                            + request.maxTokens()
                            + StringUtils.defaultString(resolvedReasoningEffort));

                    return localStorage.getOrPutString(
                                    tool,
                                    CACHE_SOURCE,
                                    promptHash,
                                    NumberUtils.toInt(ttlDays, DEFAULT_CACHE_TTL_DAYS) * 24L * 60L * 60L,
                                    () -> callReducingContext(ragDocs, prompt, modelName, resolvedReasoningEffort, request, MAX_CONTEXT_REDUCTIONS))
                            .result();
                })
                .toList();

        return ragDocs.updateResponses(responses);
    }

    private <T> ClaudeRequest buildRequest(
            final RagMultiDocumentContext<T> ragDocs,
            final String prompt,
            final String modelName,
            @Nullable final String resolvedReasoningEffort,
            final int maxChars) {
        // No individual context can be longer than the maxChars.
        // This ensures we always have at least some context available.
        final List<RagDocumentContext<T>> trimmedItems = ragDocs.getIndividualContexts().stream()
                .map(ragDoc -> ragDoc.updateDocument(ragDoc.document().substring(0, Math.min(maxChars, ragDoc.document().length()))))
                .toList();

        // Limit the total size of the context messages to maxChars
        final List<RagDocumentContext<T>> trimmedList = listLimiter.limitListContent(trimmedItems,
                RagDocumentContext::document,
                maxChars);

        // The context is sent before the prompt so the shared prefix can be cached by the Claude API
        final List<String> messages = new ArrayList<>(trimmedList.stream()
                .map(ragDoc -> ragDoc.contextLabel() + ": " + ragDoc.document())
                .toList());
        messages.add(prompt);

        return new ClaudeRequest(
                modelName,
                getMaxOutputTokens(),
                ragDocs.instructions(),
                messages,
                resolvedReasoningEffort);
    }

    /**
     * Our token count is only an estimate based on the number of characters. If Claude rejects the request as too
     * long, retrying the same request can never succeed, so we shrink the context and try again instead.
     */
    private <T> String callReducingContext(
            final RagMultiDocumentContext<T> ragDocs,
            final String prompt,
            final String modelName,
            @Nullable final String resolvedReasoningEffort,
            final ClaudeRequest request,
            final int reductionsLeft) {
        try {
            return call(request);
        } catch (final PromptTooLong ex) {
            if (reductionsLeft <= 0) {
                throw ex;
            }

            final int reducedChars = getReducedChars(request, ex);

            logger.warning("Claude rejected a prompt of " + request.generatePromptText().length() + " characters as too long. Retrying with " + reducedChars + " characters of context.");

            return callReducingContext(
                    ragDocs,
                    prompt,
                    modelName,
                    resolvedReasoningEffort,
                    buildRequest(ragDocs, prompt, modelName, resolvedReasoningEffort, reducedChars),
                    reductionsLeft - 1);
        }
    }

    /**
     * Calculates the number of characters to use when retrying a request that was rejected as too long.
     * If the API reported the token counts we scale by the limit, otherwise we fall back to a fixed ratio.
     */
    private int getReducedChars(final ClaudeRequest request, final PromptTooLong ex) {
        final int currentChars = request.generatePromptText().length();
        final double ratio = ex.getTokens().isPresent() && ex.getMaxTokens().isPresent()
                ? (double) ex.getMaxTokens().getAsInt() / ex.getTokens().getAsInt()
                : DEFAULT_REDUCTION_RATIO;
        return Math.max(1, (int) (currentChars * ratio * REDUCTION_SAFETY_MARGIN));
    }

    private String call(final ClaudeRequest request) {
        logger.fine("Calling Claude LLM");
        logger.fine(request.generatePromptText());

        final String result = claudeMessages.createMessage(request);

        logger.info("LLM Response from " + request.model());
        logger.info(result);

        return result;
    }

    private long getMaxOutputTokens() {
        return outputTokens
                .map(t -> NumberUtils.toInt(t, DEFAULT_OUTPUT_TOKENS))
                .orElse(DEFAULT_OUTPUT_TOKENS);
    }

    private int getMaxInputTokens() {
        return inputTokens
                .map(t -> NumberUtils.toInt(t, DEFAULT_INPUT_TOKENS))
                .orElse(DEFAULT_INPUT_TOKENS);
    }

    private String getModelName(final Map<String, String> environmentSettings) {
        return MapUtils.getOrNotNullDefaultIfBlank(
                environmentSettings,
                MODEL_OVERRIDE_ENV,
                model.orElse(DEFAULT_MODEL));
    }

    @Nullable
    private String getResolvedReasoningEffort(final Map<String, String> environmentSettings) {
        return MapUtils.getOrDefaultIfBlank(
                environmentSettings,
                REASONING_EFFORT_OVERRIDE_ENV,
                reasoningEffort.filter(StringUtils::isNotBlank).orElse(null));
    }

    private int getMaxChars(final Map<String, String> environmentSettings) {
        final int contextWindow = Try.of(() -> Integer.parseInt(environmentSettings.getOrDefault(CONTEXT_WINDOW_OVERRIDE_ENV, getMaxInputTokens() + "")))
                .getOrElse(getMaxInputTokens());
        return (int) (contextWindow * DEFAULT_CHARS_PER_INPUT_TOKENS);
    }
}
