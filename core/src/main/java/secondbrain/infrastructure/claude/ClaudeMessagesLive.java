package secondbrain.infrastructure.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.exceptions.LlmCallFailure;
import secondbrain.domain.exceptions.RateLimit;

import java.util.stream.Collectors;

/**
 * Adapts the Anthropic SDK client to the {@link ClaudeMessages} interface.
 */
public class ClaudeMessagesLive implements ClaudeMessages {
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String fallbacks;

    /**
     * @param client    The Anthropic SDK client
     * @param fallbacks The server-side refusal fallback mode, e.g. "default". A blank value disables fallbacks.
     */
    public ClaudeMessagesLive(final AnthropicClient client, final String fallbacks) {
        this.client = client;
        this.fallbacks = fallbacks;
    }

    @Override
    public String createMessage(final ClaudeRequest request) {
        final MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                // Cache the system prompt and context, which are shared by every prompt sent for the same documents
                .cacheControl(CacheControlEphemeral.builder().build());

        if (StringUtils.isNotBlank(request.system())) {
            builder.system(request.system());
        }

        if (StringUtils.isNotBlank(request.effort())) {
            builder.outputConfig(OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(request.effort()))
                    .build());
        }

        if (StringUtils.isNotBlank(fallbacks)) {
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from(fallbacks));
        }

        request.messages().forEach(builder::addUserMessage);

        final Message message = send(builder.build());

        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new LlmCallFailure("Claude declined the request: " + message.stopDetails()
                    .map(details -> details.category() + " " + details.explanation())
                    .orElse("no details"));
        }

        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }

    private Message send(final MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (final RateLimitException ex) {
            throw new RateLimit("Claude API rate limit exceeded", ex);
        } catch (final AnthropicException ex) {
            throw new LlmCallFailure("Failed to call the Claude API for model " + params.model(), ex);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
