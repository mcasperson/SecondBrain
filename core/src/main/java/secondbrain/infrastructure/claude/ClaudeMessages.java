package secondbrain.infrastructure.claude;

/**
 * Exposes the Claude Messages API operations used by {@link ClaudeClient} behind an interface.
 * This adapter allows the Anthropic SDK client to be swapped for a test instance.
 */
public interface ClaudeMessages {
    /**
     * Sends a request to the Messages API.
     *
     * @param request The request to send
     * @return The text of the response
     * @throws secondbrain.domain.exceptions.RateLimit      if the API rate limit was exceeded
     * @throws secondbrain.domain.exceptions.LlmCallFailure if the call failed or Claude declined the request
     */
    String createMessage(ClaudeRequest request);

    /**
     * Releases any resources held by the client.
     */
    void close();
}
