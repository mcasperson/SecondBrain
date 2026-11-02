package secondbrain.infrastructure.claude;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkState;

/**
 * A factory that creates the Anthropic SDK client used by {@link ClaudeClient}.
 * The client is application scoped, so it is created when it is first used and shared by every call.
 * Tests replace this class with their own producer method that returns a test instance of ClaudeMessages.
 */
@ApplicationScoped
public class ClaudeMessagesProducer {
    private static final int API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 10;
    private static final int API_RETRIES = 5;

    @Inject
    @ConfigProperty(name = "sb.claudellm.apikey")
    private Optional<String> apiKey;

    /**
     * An optional base URL, e.g. for a proxy. Defaults to the Claude API.
     */
    @Inject
    @ConfigProperty(name = "sb.claudellm.url")
    private Optional<String> url;

    @Inject
    @ConfigProperty(name = "sb.claudellm.timeOutSeconds", defaultValue = API_CALL_TIMEOUT_SECONDS_DEFAULT + "")
    private Long timeout;

    /**
     * The number of times the SDK retries connection errors, rate limits and server errors.
     */
    @Inject
    @ConfigProperty(name = "sb.claudellm.retryCount", defaultValue = API_RETRIES + "")
    private Integer retryCount;

    /**
     * The server-side refusal fallback mode. When Claude declines a request, the API reruns it on a fallback model.
     * Disabled by default because the default model, Claude Haiku, does not support it. Set to "default" when
     * using a model that does, such as Claude Opus or Claude Sonnet.
     */
    @Inject
    @ConfigProperty(name = "sb.claudellm.fallbacks", defaultValue = "")
    private Optional<String> fallbacks;

    @Inject
    private Logger logger;

    @Produces
    @ApplicationScoped
    public ClaudeMessages produceClaudeMessages() {
        checkState(apiKey.filter(StringUtils::isNotBlank).isPresent(),
                "Claude API Key is not configured. Please set sb.claudellm.apikey");

        final AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey.get())
                .timeout(Duration.ofSeconds(timeout))
                .maxRetries(retryCount);

        url.filter(StringUtils::isNotBlank).ifPresent(builder::baseUrl);

        return new ClaudeMessagesLive(builder.build(), fallbacks.orElse(""));
    }

    public void disposeClaudeMessages(@Disposes final ClaudeMessages claudeMessages) {
        Try.run(claudeMessages::close)
                .onFailure(ex -> logger.warning("Failed to close the Claude client: " + ex.getMessage()));
    }
}
