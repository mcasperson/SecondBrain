package secondbrain.domain.httpclient;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import secondbrain.domain.timeout.TimeoutFunctionCallback;
import secondbrain.domain.timeout.TimeoutService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TimeoutTryHttpClientCalled}. The timeout service is replaced with a test instance that records
 * the timeout settings and runs the callback a configurable number of times to simulate retries.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(TimeoutTryHttpClientCalled.class)
class TimeoutTryHttpClientCalledTest {
	private final RecordingTimeoutService timeoutService = new RecordingTimeoutService();
	private final List<Client> clients = new ArrayList<>();
	private final List<Response> responses = new ArrayList<>();
	private final TimeoutFunctionCallback<String> onTimeout = () -> "timed out";

	@Inject
	private TimeoutTryHttpClientCalled caller;

	@Produces
	TimeoutService produceTimeoutService() {
		return timeoutService;
	}

	private Client buildClient() {
		final Client client = mock(Client.class);
		clients.add(client);
		return client;
	}

	private Response callClient(final Client client) {
		final Response response = mock(Response.class);
		when(response.getStatus()).thenReturn(200 + clients.indexOf(client));
		responses.add(response);
		return response;
	}

	@Test
	void returnsTheParsedResponseAndPassesTheTimeoutSettings() {
		final String result = caller.call(
				this::buildClient,
				this::callClient,
				response -> "status " + response.getStatus(),
				RuntimeException::new,
				onTimeout,
				7,
				11,
				3);

		assertEquals("status 200", result);
		assertSame(onTimeout, timeoutService.onTimeout);
		assertEquals(7, timeoutService.timeoutSeconds);
		assertEquals(3, timeoutService.retryCount);
		assertEquals(11, timeoutService.retryDelaySeconds);
	}

	@Test
	void closesTheClientAndResponseAfterEachCall() {
		caller.call(this::buildClient, this::callClient, response -> "ok", RuntimeException::new, onTimeout, 1, 1, 0);

		verify(clients.getFirst()).close();
		verify(responses.getFirst()).close();
	}

	@Test
	void buildsANewClientForEachAttempt() {
		timeoutService.attempts = 2;

		final String result = caller.call(
				this::buildClient,
				this::callClient,
				response -> "status " + response.getStatus(),
				RuntimeException::new,
				onTimeout,
				1,
				1,
				1);

		assertEquals("status 201", result);
		assertEquals(2, clients.size());
		assertNotSame(clients.get(0), clients.get(1));
		verify(clients.get(0)).close();
		verify(clients.get(1)).close();
	}

	@Test
	void failuresAreWrappedByTheExceptionBuilderAndResourcesAreClosed() {
		final IllegalStateException parseFailure = new IllegalStateException("bad body");

		final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> caller.call(
				this::buildClient,
				this::callClient,
				response -> {
					throw parseFailure;
				},
				cause -> new IllegalArgumentException("call failed", cause),
				onTimeout,
				1,
				1,
				0));

		assertEquals("call failed", thrown.getMessage());
		assertSame(parseFailure, thrown.getCause());
		verify(clients.getFirst()).close();
		verify(responses.getFirst()).close();
	}

	@Test
	void aClientThatCanNotBeBuiltIsWrappedByTheExceptionBuilder() {
		final IllegalStateException buildFailure = new IllegalStateException("no client");

		final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> caller.call(
				() -> {
					throw buildFailure;
				},
				this::callClient,
				response -> "ok",
				cause -> new IllegalArgumentException("call failed", cause),
				onTimeout,
				1,
				1,
				0));

		assertEquals("call failed", thrown.getMessage());
		assertSame(buildFailure, thrown.getCause());
		assertTrue(responses.isEmpty());
	}

	/**
	 * Records the timeout settings and runs the callback synchronously {@link #attempts} times, returning the last
	 * result.
	 */
	private static final class RecordingTimeoutService implements TimeoutService {
		private int attempts = 1;
		@Nullable
		private TimeoutFunctionCallback<?> onTimeout;
		private long timeoutSeconds = -1;
		private int retryCount = -1;
		private long retryDelaySeconds = -1;

		@Override
		public <T> T executeWithTimeout(final TimeoutFunctionCallback<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T executeWithTimeout(final CompletableFuture<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T executeWithTimeoutAndRetry(final TimeoutFunctionCallback<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds, final int retryCount) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T executeWithTimeoutAndRetry(final TimeoutFunctionCallback<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds, final int retryCount, final long retryDelaySeconds) {
			this.onTimeout = onTimeout;
			this.timeoutSeconds = timeoutSeconds;
			this.retryCount = retryCount;
			this.retryDelaySeconds = retryDelaySeconds;
			T result = callback.apply();
			for (int i = 1; i < attempts; i++) {
				result = callback.apply();
			}
			return result;
		}
	}
}
