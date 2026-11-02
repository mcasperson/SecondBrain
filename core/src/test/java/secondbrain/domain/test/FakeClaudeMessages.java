package secondbrain.domain.test;

import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.claude.ClaudeMessages;
import secondbrain.infrastructure.claude.ClaudeRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory test instance of ClaudeMessages. Expose it to the class being tested with a @Produces method.
 */
public class FakeClaudeMessages implements ClaudeMessages {
    private final List<ClaudeRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger closes = new AtomicInteger();
    private volatile String response = "response";
    @Nullable
    private volatile RuntimeException failure;

    /**
     * Sets the text returned by every subsequent call.
     */
    public void respondWith(final String response) {
        this.response = response;
    }

    /**
     * Makes every subsequent call throw the supplied exception.
     */
    public void failWith(final RuntimeException failure) {
        this.failure = failure;
    }

    /**
     * Returns every request passed to createMessage, in the order the calls were made.
     */
    public List<ClaudeRequest> getRequests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public int getCloseCount() {
        return closes.get();
    }

    @Override
    public String createMessage(final ClaudeRequest request) {
        requests.add(request);
        final RuntimeException currentFailure = failure;
        if (currentFailure != null) {
            throw currentFailure;
        }
        return response;
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }
}
